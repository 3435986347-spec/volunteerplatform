package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivityViolationMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
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

    private static final int ENROLL_APPROVED = 1;
    /** 确认到家「超时」分界：活动结束后 1 小时 */
    private static final long CONFIRM_HOME_WINDOW_HOURS = 1;
    /** 签到二维码内容前缀（占位，前端据此识别并打开 GPS 签到页） */
    private static final String CHECKIN_QR_PREFIX = "hengde-activity-checkin:";

    private ActivityEnrollmentMapper enrollmentMapper;
    private ActivityMapper activityMapper;
    private ActivityAttendanceMapper attendanceMapper;
    private ActivityViolationMapper violationMapper;
    private ActivityLeaderService activityLeaderService;

    @Autowired
    public void setEnrollmentMapper(ActivityEnrollmentMapper enrollmentMapper) {
        this.enrollmentMapper = enrollmentMapper;
    }

    @Autowired
    public void setActivityMapper(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
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

    /** 我的活动列表：本人已通过报名的活动（按活动开始时间倒序），带考勤/违规/负责人姓名摘要。 */
    public List<MyActivityVO> myActivities(Long volunteerId) {
        List<Long> activityIds = approvedActivityIds(volunteerId);
        if (activityIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Activity> activityById = new HashMap<>();
        for (Activity a : activityMapper.selectBatchIds(activityIds)) {
            activityById.put(a.getId(), a);
        }
        Map<Long, ActivityAttendance> attByActivity = attendanceByActivity(volunteerId, activityIds);
        Map<Long, Integer> violationCnt = violationCountByActivity(volunteerId, activityIds);

        return activityIds.stream()
                .filter(activityById::containsKey)
                .sorted((a, b) -> compareByStartDesc(activityById.get(a), activityById.get(b)))
                .map(aid -> {
                    MyActivityVO vo = new MyActivityVO();
                    fillBase(vo, activityById.get(aid), attByActivity.get(aid),
                            violationCnt.getOrDefault(aid, 0), leaderNames(aid));
                    return vo;
                }).toList();
    }

    /** 我的活动详情：校验本人确属该活动已通过报名后，返回摘要 + 坐标/二维码 + 负责人 + 确认到家 + 评价回显。 */
    public MyActivityDetailVO myActivityDetail(Long volunteerId, Long activityId) {
        requireApprovedEnrollment(volunteerId, activityId);
        Activity a = activityMapper.selectById(activityId);
        if (a == null) {
            throw new BusinessException("活动不存在");
        }
        ActivityAttendance att = findAttendance(volunteerId, activityId);
        int violationCount = violationCountByActivity(volunteerId, List.of(activityId)).getOrDefault(activityId, 0);
        List<ActivityLeaderVO> leaders = activityLeaderService.list(activityId);

        MyActivityDetailVO vo = new MyActivityDetailVO();
        fillBase(vo, a, att, violationCount,
                leaders.stream().map(ActivityLeaderVO::getVolunteerName).filter(Objects::nonNull).toList());
        vo.setLocation(a.getLocation());
        vo.setLat(a.getLat());
        vo.setLng(a.getLng());
        vo.setCheckInRadiusM(a.getCheckInRadiusM());
        vo.setLeaders(leaders);
        vo.setCheckInQrContent(CHECKIN_QR_PREFIX + activityId);
        if (att != null) {
            vo.setConfirmHomeTime(att.getConfirmHomeTime());
            vo.setConfirmHomeOverdue(isConfirmHomeOverdue(a, att.getConfirmHomeTime()));
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

    private List<Long> approvedActivityIds(Long volunteerId) {
        return enrollmentMapper.selectList(Wrappers.<ActivityEnrollment>lambdaQuery()
                        .eq(ActivityEnrollment::getVolunteerId, volunteerId)
                        .eq(ActivityEnrollment::getStatus, ENROLL_APPROVED))
                .stream().map(ActivityEnrollment::getActivityId).distinct().toList();
    }

    private void requireApprovedEnrollment(Long volunteerId, Long activityId) {
        Long c = enrollmentMapper.selectCount(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, activityId)
                .eq(ActivityEnrollment::getVolunteerId, volunteerId)
                .eq(ActivityEnrollment::getStatus, ENROLL_APPROVED));
        if (c == null || c == 0) {
            throw new BusinessException("活动不存在或您未参加");
        }
    }

    private Map<Long, ActivityAttendance> attendanceByActivity(Long volunteerId, List<Long> activityIds) {
        Map<Long, ActivityAttendance> map = new HashMap<>();
        for (ActivityAttendance att : attendanceMapper.selectList(Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getVolunteerId, volunteerId)
                .in(ActivityAttendance::getActivityId, activityIds))) {
            map.put(att.getActivityId(), att);
        }
        return map;
    }

    private Map<Long, Integer> violationCountByActivity(Long volunteerId, List<Long> activityIds) {
        Map<Long, Integer> map = new HashMap<>();
        for (ActivityViolation v : violationMapper.selectList(Wrappers.<ActivityViolation>lambdaQuery()
                .eq(ActivityViolation::getVolunteerId, volunteerId)
                .in(ActivityViolation::getActivityId, activityIds))) {
            map.merge(v.getActivityId(), 1, Integer::sum);
        }
        return map;
    }

    private List<String> leaderNames(Long activityId) {
        return activityLeaderService.list(activityId).stream()
                .map(ActivityLeaderVO::getVolunteerName).filter(Objects::nonNull).toList();
    }

    private ActivityAttendance findAttendance(Long volunteerId, Long activityId) {
        return attendanceMapper.selectOne(Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getActivityId, activityId)
                .eq(ActivityAttendance::getVolunteerId, volunteerId)
                .last("limit 1"));
    }

    private boolean isConfirmHomeOverdue(Activity a, LocalDateTime confirmHomeTime) {
        return confirmHomeTime != null && a.getEndTime() != null
                && confirmHomeTime.isAfter(a.getEndTime().plusHours(CONFIRM_HOME_WINDOW_HOURS));
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
