package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.config.ActivityProperties;
import com.hengde.activity.constant.AttendanceQr;
import com.hengde.activity.constant.EnrollmentStatus;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivityViolationMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.entity.ActivityEnrollment;
import com.hengde.activity.entity.ActivityViolation;
import com.hengde.activity.vo.ActivityLeaderVO;
import com.hengde.activity.vo.MyActivityDetailVO;
import com.hengde.activity.vo.MyActivityVO;
import com.hengde.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 志愿者端「我的活动」：以本人「已通过报名」的活动为基，聚合考勤/违规/负责人/积分等摘要。
 *
 * <p>区别于「我负责的活动」（{@link AttendanceService#myLedActivities}，那是负责人视角）：本视图是
 * 普通参与者视角，含签到状态、是否违规、确认到家、双向评价回显，并给详情提供 GPS 自助签到所需的坐标/半径。</p>
 *
 * @author hengde
 */
@Service
public class MyActivityService {

    private static final int ENROLL_APPROVED = EnrollmentStatus.APPROVED;
    /** 确认到家「超时」分界：活动结束后 1 小时 */
    private static final long CONFIRM_HOME_WINDOW_HOURS = 1;

    private ActivityEnrollmentMapper enrollmentMapper;
    private ActivityMapper activityMapper;
    private ActivitySlotMapper slotMapper;

    private ActivityAttendanceMapper attendanceMapper;
    private ActivityViolationMapper violationMapper;
    private ActivityLeaderService activityLeaderService;
    private ActivityProperties activityProperties;

    @Autowired
    public void setActivityProperties(ActivityProperties activityProperties) {
        this.activityProperties = activityProperties;
    }

    @Autowired
    public void setEnrollmentMapper(ActivityEnrollmentMapper enrollmentMapper) {
        this.enrollmentMapper = enrollmentMapper;
    }

    @Autowired
    public void setActivityMapper(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    @Autowired
    public void setSlotMapper(ActivitySlotMapper slotMapper) {
        this.slotMapper = slotMapper;
    }

    @Autowired
    public void setAttendanceMapper(ActivityAttendanceMapper attendanceMapper) {
        this.attendanceMapper = attendanceMapper;
    }

    @Autowired
    public void setViolationMapper(ActivityViolationMapper violationMapper) {
        this.violationMapper = violationMapper;
    }

    @Autowired
    public void setActivityLeaderService(ActivityLeaderService activityLeaderService) {
        this.activityLeaderService = activityLeaderService;
    }

    /**
     * 我的活动列表——<b>按场次</b>列出：本人已通过报名的每一个场次一条（按活动开始时间倒序）。
     *
     * <p><b>V30 变更</b>：此前按活动聚合，一人报同一活动两场时
     * {@code map.put(activityId, att)} 会让<b>后一条静默覆盖前一条</b>，
     * 界面只显示其中一场且不确定是哪一场。依据原型 P15／P92，考勤本就是场次粒度，故改为逐场次返回。</p>
     */
    public List<MyActivityVO> myActivities(Long volunteerId) {
        List<ActivityEnrollment> enrolls = approvedEnrollments(volunteerId);
        if (enrolls.isEmpty()) {
            return List.of();
        }
        List<Long> activityIds = enrolls.stream().map(ActivityEnrollment::getActivityId).distinct().toList();
        Map<Long, Activity> activityById = new HashMap<>();
        for (Activity a : activityMapper.selectBatchIds(activityIds)) {
            activityById.put(a.getId(), a);
        }
        Map<Long, ActivitySlot> slotById = new HashMap<>();
        List<Long> slotIds = enrolls.stream().map(ActivityEnrollment::getSlotId)
                .filter(Objects::nonNull).distinct().toList();
        if (!slotIds.isEmpty()) {
            for (ActivitySlot s : slotMapper.selectBatchIds(slotIds)) {
                slotById.put(s.getId(), s);
            }
        }
        Map<String, ActivityAttendance> attBySlot = attendanceBySlot(volunteerId, activityIds);
        Map<String, Integer> violationCnt = violationCountBySlot(volunteerId, activityIds);

        return enrolls.stream()
                .filter(e -> activityById.containsKey(e.getActivityId()))
                .sorted((x, y) -> compareByStartDesc(activityById.get(x.getActivityId()),
                        activityById.get(y.getActivityId())))
                .map(e -> {
                    Long aid = e.getActivityId();
                    MyActivityVO vo = new MyActivityVO();
                    fillBase(vo, activityById.get(aid), attBySlot.get(attKey(aid, e.getSlotId())),
                            violationCnt.getOrDefault(attKey(aid, e.getSlotId()), 0), leaderNames(aid));
                    vo.setSlotId(e.getSlotId());
                    ActivitySlot s = slotById.get(e.getSlotId());
                    if (s != null) {
                        vo.setSlotProjectName(s.getProjectName());
                        vo.setSlotStartTime(s.getStartTime());
                        vo.setSlotEndTime(s.getEndTime());
                    }
                    return vo;
                }).toList();
    }

    /** 我的活动详情：校验本人确属该活动已通过报名后，返回摘要 + 坐标/二维码 + 负责人 + 确认到家 + 评价回显。 */
    public MyActivityDetailVO myActivityDetail(Long volunteerId, Long activityId, Long slotId) {
        // 校验落到【这一场】：只报了上午场的人不该看到下午场的详情（含二维码与负责人联系方式）。
        requireApprovedEnrollmentOfSlot(volunteerId, activityId, slotId);
        Activity a = activityMapper.selectById(activityId);
        if (a == null) {
            throw new BusinessException("活动不存在");
        }
        ActivitySlot slot = slotMapper.selectById(slotId);
        if (slot == null || !activityId.equals(slot.getActivityId())) {
            throw new BusinessException("活动时间段不存在");
        }
        ActivityAttendance att = findAttendance(volunteerId, activityId, slotId);
        int violationCount = violationCountBySlot(volunteerId, List.of(activityId))
                .getOrDefault(attKey(activityId, slotId), 0);
        List<ActivityLeaderVO> leaders = activityLeaderService.list(activityId);

        MyActivityDetailVO vo = new MyActivityDetailVO();
        fillBase(vo, a, att, violationCount,
                leaders.stream().map(ActivityLeaderVO::getVolunteerName).filter(Objects::nonNull).toList());
        vo.setLocation(a.getLocation());
        vo.setLat(a.getLat());
        vo.setLng(a.getLng());
        vo.setCheckInRadiusM(a.getCheckInRadiusM());
        vo.setLeaders(leaders);
        vo.setCheckInQrContent(AttendanceQr.checkInContent(activityId));
        vo.setEmergencyPhone(activityProperties.getEmergencyPhone());
        // 场次回填：列表行有这几项，详情不能反而没有（否则前端从列表点进详情会丢失「这是哪一场」）。
        vo.setSlotId(slotId);
        vo.setSlotProjectName(slot.getProjectName());
        vo.setSlotStartTime(slot.getStartTime());
        vo.setSlotEndTime(slot.getEndTime());
        if (att != null) {
            vo.setConfirmHomeTime(att.getConfirmHomeTime());
            vo.setConfirmHomeOverdue(isConfirmHomeOverdue(a, slot, att.getConfirmHomeTime()));
            vo.setMyActivityScore(att.getVolActivityScore());
            vo.setMyLeaderScore(att.getVolLeaderScore());
            vo.setMyComment(att.getVolComment());
            vo.setLeaderEvaluationOfMe(att.getLeaderEvaluation());
        }
        return vo;
    }

    // ---------- 内部 ----------

    private void fillBase(MyActivityVO vo, Activity a, ActivityAttendance att, int violationCount,
                          List<String> leaderNames) {
        vo.setActivityId(a.getId());
        vo.setSerialNo(a.getSerialNo());
        vo.setTitle(a.getTitle());
        vo.setStartTime(a.getStartTime());
        vo.setEndTime(a.getEndTime());
        vo.setRunStatus(a.getRunStatus());
        vo.setLeaderNames(leaderNames);
        vo.setViolationCount(violationCount);
        if (att != null) {
            vo.setAttendStatus(att.getAttendStatus());
            vo.setCheckInTime(att.getCheckInTime());
            vo.setCheckOutTime(att.getCheckOutTime());
            vo.setServiceMinutes(att.getServiceMinutes());
            vo.setSecretaryStatus(att.getSecretaryStatus());
            vo.setPointsStatus(att.getPointsStatus());
            vo.setPointsAward(att.getPointsAward());
        }
    }

    /**
     * 详情鉴权下沉到场次（V30）：必须报了<b>这一场</b>且已通过。
     *
     * <p>旧版只校验「报过这个活动」，于是报了上午场的人可以传下午场的 {@code slotId} 拿到详情——
     * 而详情里含签到二维码内容、负责人姓名与紧急联系电话。</p>
     */
    private void requireApprovedEnrollmentOfSlot(Long volunteerId, Long activityId, Long slotId) {
        Long c = enrollmentMapper.selectCount(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, activityId)
                .eq(ActivityEnrollment::getSlotId, slotId)
                .eq(ActivityEnrollment::getVolunteerId, volunteerId)
                .eq(ActivityEnrollment::getStatus, ENROLL_APPROVED));
        if (c == null || c == 0) {
            throw new BusinessException("活动不存在或您未参加该时间段");
        }
    }

    private static String attKey(Long activityId, Long slotId) {
        return activityId + ":" + slotId;
    }

    /**
     * 按「活动 + 场次」索引考勤（V30）。
     *
     * <p>旧版以 {@code activityId} 为键，多场次时后一条会覆盖前一条——
     * 那不是「取其中一条」而是「随机丢弃其余条」，界面显示哪一场取决于查询返回顺序。</p>
     */
    private Map<String, ActivityAttendance> attendanceBySlot(Long volunteerId, List<Long> activityIds) {
        Map<String, ActivityAttendance> map = new HashMap<>();
        for (ActivityAttendance att : attendanceMapper.selectList(Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getVolunteerId, volunteerId)
                .in(ActivityAttendance::getActivityId, activityIds))) {
            map.put(attKey(att.getActivityId(), att.getSlotId()), att);
        }
        return map;
    }

    private List<ActivityEnrollment> approvedEnrollments(Long volunteerId) {
        return enrollmentMapper.selectList(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getVolunteerId, volunteerId)
                .eq(ActivityEnrollment::getStatus, ENROLL_APPROVED));
    }

    /**
     * 违规计数按「活动 + 场次」（V30）。
     *
     * <p>列表行已是场次粒度，若计数仍按活动汇总，同一活动的每一行都会显示<b>整个活动</b>的违规数——
     * 上午场没违规的人也会看到「违规 1 次」。违规自 V30 起带 {@code slot_id}（依据 xlsx Row 32），故按场次分组。</p>
     *
     * <p><b>只计已通过组织部审核的（V32，第 5 批）</b>：xlsx Row 41 F「各类违规记录和奖励均需
     * <b>组织部同学审核才可显示</b>」。此前是负责人现场一记、志愿者这里立刻看到条数，
     * 中间没有任何闸门——等于把负责人的一面之词直接当定论呈现给被记的那个人。</p>
     *
     * <p><b>负责人端刻意不走这条口径</b>：{@code AttendanceService} 的名单与违规记录列表仍返回全部，
     * 那是负责人自己的工作底稿——看不到刚记的那条反而没法复核与更正。</p>
     */
    private Map<String, Integer> violationCountBySlot(Long volunteerId, List<Long> activityIds) {
        Map<String, Integer> map = new HashMap<>();
        for (ActivityViolation v : violationMapper.selectList(Wrappers.<ActivityViolation>lambdaQuery()
                .eq(ActivityViolation::getVolunteerId, volunteerId)
                .eq(ActivityViolation::getReviewStatus, ActivityViolation.REVIEW_APPROVED)
                .in(ActivityViolation::getActivityId, activityIds))) {
            map.merge(attKey(v.getActivityId(), v.getSlotId()), 1, Integer::sum);
        }
        return map;
    }

    private List<String> leaderNames(Long activityId) {
        return activityLeaderService.list(activityId).stream()
                .map(ActivityLeaderVO::getVolunteerName).filter(Objects::nonNull).toList();
    }

    /**
     * 取本人在某活动<b>某场次</b>的考勤（V30）。
     *
     * <p>旧签名不带 {@code slotId} 且以 {@code limit 1} 收尾——多场次时返回哪一场
     * 取决于查询顺序，界面会显示一个「不确定是哪一场」的考勤。</p>
     */
    private ActivityAttendance findAttendance(Long volunteerId, Long activityId, Long slotId) {
        return attendanceMapper.selectOne(Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getActivityId, activityId)
                .eq(ActivityAttendance::getSlotId, slotId)
                .eq(ActivityAttendance::getVolunteerId, volunteerId)
                .last("limit 1"));
    }

    /**
     * 确认到家是否超时——基准是<b>本场次</b>结束时间，不是活动整体结束时间。
     *
     * <p><b>需求依据</b>：xlsx Row 32 C「在活动结束一小时内，志愿者需要点击确认到家」，
     * 「活动结束」按 2026-07-31 拍板的「时间窗按场次」读作<b>他那一场</b>的结束。</p>
     *
     * <p>按活动整体判会两头都错：9:00–18:00 的活动里，上午 9:00–12:00 那场的人 17:00 才想起来点，
     * 距他散场已 5 小时，却因为没过 18:00+1h 而<b>不算超时</b>；反过来他 12:30 正常点，
     * 若活动整体 12:00 就结束（负责人提前收尾）又会被误判。</p>
     */
    private boolean isConfirmHomeOverdue(Activity a, ActivitySlot slot, LocalDateTime confirmHomeTime) {
        LocalDateTime end = slot != null && slot.getEndTime() != null ? slot.getEndTime() : a.getEndTime();
        return confirmHomeTime != null && end != null
                && confirmHomeTime.isAfter(end.plusHours(CONFIRM_HOME_WINDOW_HOURS));
    }

    private int compareByStartDesc(Activity a, Activity b) {
        LocalDateTime sa = a.getStartTime();
        LocalDateTime sb = b.getStartTime();
        if (sa == null && sb == null) {
            return Long.compare(b.getId(), a.getId());
        }
        if (sa == null) {
            return 1;
        }
        if (sb == null) {
            return -1;
        }
        int c = sb.compareTo(sa);
        return c != 0 ? c : Long.compare(b.getId(), a.getId());
    }
}
