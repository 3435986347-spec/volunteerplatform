package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.config.ActivityProperties;
import com.hengde.auth.constant.SanctionScope;
import com.hengde.auth.service.SanctionQueryService;
import com.hengde.activity.constant.ActivityStatus;
import com.hengde.activity.constant.AttendStatus;
import com.hengde.activity.constant.AttendanceQr;
import com.hengde.activity.constant.EnrollmentStatus;
import com.hengde.activity.constant.LeaderType;
import com.hengde.activity.constant.RunStatus;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dao.ActivityLeaderMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivityViolationMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.entity.ActivityEnrollment;
import com.hengde.activity.entity.ActivityLeader;
import com.hengde.activity.entity.ActivityViolation;
import com.hengde.activity.vo.AttendanceRosterVO;
import com.hengde.activity.vo.ManagedActivityDetailVO;
import com.hengde.activity.vo.ManagedActivityVO;
import com.hengde.activity.vo.ViolationRecordVO;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerDisplayView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.qrcode.QrCodeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 活动现场考勤：负责人点开始/结束、志愿者 GPS 自助签到、负责人标到位状态/记违规/统一签退算时长，
 * 以及负责人视角的「我负责的活动」列表与详情名单。
 *
 * <p>鉴权在 controller：志愿者端经 {@link ActivityLeaderService#requireVolunteerLeader} 限本活动负责人，
 * 管理端经 {@code @SaCheckPermission(activity:manage)}；service 仅按 operatorId 记录操作人。</p>
 *
 * <p>时间窗口：签到 = 活动开始前 2h 起至结束后 2h；统一签退 = 活动结束后 2h 内。
 * 服务时长 = 签退 − 签到（分钟）；请假/缺席记 0，缺席并自动记一条违规。</p>
 *
 * @author hengde
 */
@Service
public class AttendanceService {

    private static final int ACTIVITY_PUBLISHED = ActivityStatus.PUBLISHED;
    private static final int ENROLL_APPROVED = EnrollmentStatus.APPROVED;

    private static final int RUN_NOT_STARTED = RunStatus.NOT_STARTED;
    private static final int RUN_RUNNING = RunStatus.RUNNING;
    private static final int RUN_ENDED = RunStatus.ENDED;

    private static final int ATTEND_NORMAL = AttendStatus.NORMAL;
    private static final int ATTEND_LEAVE = AttendStatus.LEAVE;
    private static final int ATTEND_LATE = AttendStatus.LATE;
    private static final int ATTEND_ABSENT = AttendStatus.ABSENT;

    private static final int CHECKIN_SCAN = 1;
    private static final int CHECKIN_AUTO = 2;
    private static final int CHECKIN_LEADER = 3;

    private static final int VIOLATION_ABSENT = 5;

    private static final long CHECKIN_OPEN_BEFORE_HOURS = 2;
    private static final long CHECKOUT_WINDOW_AFTER_HOURS = 2;

    private static final double EARTH_RADIUS_M = 6_371_000;

    private static final int LEADER_TYPE_VOLUNTEER = LeaderType.VOLUNTEER;

    private ActivityMapper activityMapper;
    private ActivitySlotMapper slotMapper;

    private ActivityAttendanceMapper attendanceMapper;
    private ActivityViolationMapper violationMapper;
    private ActivityEnrollmentMapper enrollmentMapper;
    private SanctionQueryService sanctionQueryService;
    private ActivityLeaderMapper leaderMapper;
    private VolunteerQueryService volunteerQueryService;
    private ActivityProperties activityProperties;

    @Autowired
    public void setActivityMapper(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    @Autowired
    public void setActivityProperties(ActivityProperties activityProperties) {
        this.activityProperties = activityProperties;
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
    public void setSanctionQueryService(SanctionQueryService sanctionQueryService) {
        this.sanctionQueryService = sanctionQueryService;
    }

    @Autowired
    public void setEnrollmentMapper(ActivityEnrollmentMapper enrollmentMapper) {
        this.enrollmentMapper = enrollmentMapper;
    }

    @Autowired
    public void setLeaderMapper(ActivityLeaderMapper leaderMapper) {
        this.leaderMapper = leaderMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    // ---------- 活动开始 / 结束 ----------

    /** 负责人点「活动开始」：未开始 → 进行中。 */
    @Transactional(rollbackFor = Exception.class)
    public void startActivity(Long activityId, Long operatorId) {
        Activity a = requirePublished(activityId);
        if (!Integer.valueOf(RUN_NOT_STARTED).equals(a.getRunStatus())) {
            throw new BusinessException("活动已开始或已结束");
        }
        a.setRunStatus(RUN_RUNNING);
        a.setActualStartTime(LocalDateTime.now());
        activityMapper.updateById(a);
    }

    /** 负责人点「活动结束」：进行中 → 已结束。 */
    @Transactional(rollbackFor = Exception.class)
    public void finishActivity(Long activityId, Long operatorId) {
        Activity a = requirePublished(activityId);
        if (!Integer.valueOf(RUN_RUNNING).equals(a.getRunStatus())) {
            throw new BusinessException("活动未在进行中，无法结束");
        }
        a.setRunStatus(RUN_ENDED);
        a.setActualEndTime(LocalDateTime.now());
        activityMapper.updateById(a);
    }

    /**
     * 生成本活动「签到二维码」的 PNG data URL，供负责人端展示、志愿者扫码（内容见 {@link AttendanceQr#checkInContent}）。
     * 鉴权（限本活动负责人）在 controller 完成；二维码非鉴权凭据，真正门槛是签到时的 GPS 距离 + 时间窗 + 报名校验。
     */
    public String checkInQrDataUrl(Long activityId) {
        return QrCodeUtil.toPngDataUrl(AttendanceQr.checkInContent(activityId), 360);
    }

    /**
     * 生成本活动「签退二维码」的 PNG data URL（内容见 {@link AttendanceQr#checkOutContent}）。鉴权同签到码在 controller。
     */
    public String checkOutQrDataUrl(Long activityId) {
        return QrCodeUtil.toPngDataUrl(AttendanceQr.checkOutContent(activityId), 360);
    }

    // ---------- 签到（志愿者自助 GPS） ----------

    /**
     * 志愿者自助签到：校验报名(已通过) + 时间窗口 + GPS 距离 ≤ 半径，落 check_in_*。重复签到拒绝。
     */
    @Transactional(rollbackFor = Exception.class)
    public void checkIn(Long activityId, Long slotId, Long volunteerId, BigDecimal lat, BigDecimal lng, Integer method) {
        // 处置闸门（V2 第 5 批）：「限制参加活动」不能只挡报名。
        // 【报名在前、处罚在后】是常见时序——人已报上名，几天后因别的事被处罚，
        // 若只挡报名，他照样能到场签到拿时长积分，那条处罚等于没执行。
        //
        // ⚠️ 边界：本闸门【不回溯取消已有报名】。取消报名是破坏性动作，
        //    需求（Row 41 / Row 73 / P109）只说「限制其使用」，没有要求撤销既有报名；
        //    真要清退应当是一次显式的、看得见的操作，不是处罚的隐藏副作用。
        sanctionQueryService.assertNotRestricted(volunteerId, SanctionScope.ACTIVITY, "签到");
        Activity a = requirePublished(activityId);
        if (a.getLat() == null || a.getLng() == null) {
            throw new BusinessException("活动未设置签到坐标，无法签到");
        }
        ActivitySlot slot = requireSlotOfActivity(activityId, slotId);
        LocalDateTime now = LocalDateTime.now();
        // 时间窗以【场次】起止为准，不是活动整体起止。
        //
        // 需求依据：xlsx Row 32「签到时间：活动时间开始 2 小时就可以签到了」字面写的是「活动时间」，
        // 其中「活动时间」对某一次参与而言指的就是【他报名的那一场】的起止——
        // 已于 2026-07-31 由需求方明确拍板「时间窗按场次」。
        // 若按活动整体起止，9:00–18:00 的活动里只报了 14:00 场的人 7:00 就能签到，与「一场一考勤」自相矛盾。
        if (slot.getStartTime() != null && now.isBefore(slot.getStartTime().minusHours(CHECKIN_OPEN_BEFORE_HOURS))) {
            throw new BusinessException("未到签到时间（该时间段开始前 2 小时开放签到）");
        }
        if (slot.getEndTime() != null && now.isAfter(slot.getEndTime().plusHours(CHECKOUT_WINDOW_AFTER_HOURS))) {
            throw new BusinessException("签到已截止");
        }
        requireApprovedEnrollmentOfSlot(activityId, slotId, volunteerId, "您未报名该时间段或报名未通过，无法签到");

        // 服务层兜底范围守卫：Haversine 三角函数有周期性，lat+360/lng+360 会让距离≈0 从而绕过半径校验。
        // 即便 CheckInDTO 已加 Bean Validation，service 入口（测试/未来内部调用）也必须自己拦。
        requireValidCoord(lat, lng);

        int radius = a.getCheckInRadiusM() == null ? 500 : a.getCheckInRadiusM();
        double dist = distanceMeters(lat, lng, a.getLat(), a.getLng());
        if (dist > radius) {
            throw new BusinessException("您距活动地点约 " + Math.round(dist) + " 米，超出签到范围（" + radius + " 米）");
        }
        int m = Integer.valueOf(CHECKIN_SCAN).equals(method) ? CHECKIN_SCAN : CHECKIN_AUTO;

        ActivityAttendance att = findAttendance(activityId, slotId, volunteerId);
        if (att != null) {
            if (att.getCheckInTime() != null) {
                throw new BusinessException("您已签到");
            }
            att.setCheckInTime(now);
            att.setCheckInMethod(m);
            att.setCheckInBy(volunteerId);
            att.setCheckInLat(lat);
            att.setCheckInLng(lng);
            if (att.getAttendStatus() == null) {
                att.setAttendStatus(ATTEND_NORMAL);
            }
            attendanceMapper.updateById(att);
            return;
        }
        att = newAttendance(activityId, slotId, volunteerId);
        att.setCheckInTime(now);
        att.setCheckInMethod(m);
        att.setCheckInBy(volunteerId);
        att.setCheckInLat(lat);
        att.setCheckInLng(lng);
        att.setAttendStatus(ATTEND_NORMAL);
        try {
            attendanceMapper.insert(att);
        } catch (DuplicateKeyException e) {
            // uk_activity_volunteer_slot：并发重复签到（同一场次）
            throw new BusinessException("您已签到");
        }
    }

    // ---------- 到位状态 / 违规（负责人） ----------

    /** 标记到位状态：缺席 → 时长 0 + 自动违规；请假 → 时长 0；正常/迟到 → 时长留待签退算。 */
    @Transactional(rollbackFor = Exception.class)
    public void markAttendStatus(Long activityId, Long slotId, Long volunteerId, Integer status, Long operatorId) {
        if (status == null || status < ATTEND_NORMAL || status > ATTEND_ABSENT) {
            throw new BusinessException("到位状态非法（1正常/2请假/3迟到/4缺席）");
        }
        requirePublished(activityId);
        requireApprovedEnrollmentOfSlot(activityId, slotId, volunteerId, "该志愿者未报名该时间段或报名未通过");

        ActivityAttendance att = findAttendance(activityId, slotId, volunteerId);
        boolean isNew = att == null;
        if (isNew) {
            att = newAttendance(activityId, slotId, volunteerId);
        }
        att.setAttendStatus(status);
        if (Integer.valueOf(ATTEND_ABSENT).equals(status) || Integer.valueOf(ATTEND_LEAVE).equals(status)) {
            att.setServiceMinutes(0);
        }
        if (isNew) {
            try {
                attendanceMapper.insert(att);
            } catch (DuplicateKeyException e) {
                att = findAttendance(activityId, slotId, volunteerId);
                att.setAttendStatus(status);
                if (Integer.valueOf(ATTEND_ABSENT).equals(status) || Integer.valueOf(ATTEND_LEAVE).equals(status)) {
                    att.setServiceMinutes(0);
                }
                attendanceMapper.updateById(att);
            }
        } else {
            attendanceMapper.updateById(att);
        }
        if (Integer.valueOf(ATTEND_ABSENT).equals(status)) {
            autoAbsentViolation(activityId, slotId, volunteerId, operatorId);
        }
    }

    /** 负责人记录违规，返回违规记录 id。 */
    @Transactional(rollbackFor = Exception.class)
    public Long recordViolation(Long activityId, Long slotId, Long volunteerId, Integer type, String description, Long operatorId) {
        // 自由文本=记录明细：必填且 ≤512（与 DB activity_violation.description 对齐，防超长截断 → 500）。
        // 注：缺席自动违规走 autoAbsentViolation 直插、不经此方法，故此处的「必填」不影响自动违规。
        if (description == null || description.isBlank()) {
            throw new BusinessException("请填写违规说明");
        }
        if (description.length() > 512) {
            throw new BusinessException("违规说明过长（不超过 512 字）");
        }
        // 类型范围兜底（防跨模块绕过 DTO）：手工违规 0~4；缺席=5 系统自动直插、不经此方法。
        // 超出 TINYINT 取值（如 128/999）会触发 DB 截断/越界异常变 500，故此处先拦。
        if (type != null && (type < 0 || type > 4)) {
            throw new BusinessException("违规类型不合法（0其他 / 1~4）");
        }
        requirePublished(activityId);
        requireApprovedEnrollmentOfSlot(activityId, slotId, volunteerId, "该志愿者未报名该时间段或报名未通过");
        ActivityViolation v = new ActivityViolation();
        v.setActivityId(activityId);
        v.setSlotId(slotId);
        v.setVolunteerId(volunteerId);
        v.setViolationType(type == null ? 0 : type);
        v.setDescription(description);
        v.setRecordedBy(operatorId);
        v.setRecordedTime(LocalDateTime.now());
        violationMapper.insert(v);
        return v.getId();
    }

    /**
     * 活动违规记录明细（名字 / 记录人 / 记录明细 / 记录时间），按记录时间倒序。供负责人板块「违规记录」页。
     *
     * <p>违规者 id 必是志愿者，姓名直接按志愿者域解析。<b>记录人 recorded_by 跨 volunteer/admin 两套 ID 空间
     * 且会重叠</b>，故仅当其属本活动「志愿者负责人」（leaderType=1）时才解析姓名，否则置 null（管理端账号录入 /
     * 已撤销负责人 / 跨域同号）——避免把 admin_user.id 错认成同号志愿者。</p>
     */
    public List<ViolationRecordVO> violationRecords(Long activityId) {
        List<ActivityViolation> rows = violationMapper.selectList(Wrappers.<ActivityViolation>lambdaQuery()
                .eq(ActivityViolation::getActivityId, activityId)
                .orderByDesc(ActivityViolation::getRecordedTime));
        if (rows.isEmpty()) {
            return List.of();
        }
        // 违规者：一定是志愿者，安全解析
        Set<Long> offenderIds = rows.stream().map(ActivityViolation::getVolunteerId).collect(Collectors.toSet());
        Map<Long, String> offenderNames = volunteerQueryService.listNamesByIds(offenderIds);
        // 记录人：仅本活动志愿者负责人（leaderType=1 的 volunteer.id）才解析姓名，避免跨域同号错认
        Set<Long> volunteerLeaderIds = leaderMapper.selectList(Wrappers.<ActivityLeader>lambdaQuery()
                        .eq(ActivityLeader::getActivityId, activityId)
                        .eq(ActivityLeader::getLeaderType, LEADER_TYPE_VOLUNTEER))
                .stream().map(ActivityLeader::getVolunteerId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> leaderNames = volunteerQueryService.listNamesByIds(volunteerLeaderIds);
        // V30：违规记在场次上，明细页必须带得出是哪一场，否则多场次活动里同名同类型的两条完全无法区分
        Map<Long, ActivitySlot> slotById = new HashMap<>();
        List<Long> slotIds = rows.stream().map(ActivityViolation::getSlotId)
                .filter(Objects::nonNull).distinct().toList();
        if (!slotIds.isEmpty()) {
            for (ActivitySlot s : slotMapper.selectBatchIds(slotIds)) {
                slotById.put(s.getId(), s);
            }
        }
        return rows.stream().map(v -> {
            ViolationRecordVO r = new ViolationRecordVO();
            r.setId(v.getId());
            r.setSlotId(v.getSlotId());
            ActivitySlot s = slotById.get(v.getSlotId());
            if (s != null) {
                r.setSlotProjectName(s.getProjectName());
                r.setSlotStartTime(s.getStartTime());
                r.setSlotEndTime(s.getEndTime());
            }
            r.setVolunteerId(v.getVolunteerId());
            r.setVolunteerName(offenderNames.get(v.getVolunteerId()));
            r.setViolationType(v.getViolationType());
            r.setDescription(v.getDescription());
            Long rb = v.getRecordedBy();
            r.setRecordedBy(rb);
            r.setRecordedByName(rb != null && volunteerLeaderIds.contains(rb) ? leaderNames.get(rb) : null);
            r.setRecordedTime(v.getRecordedTime());
            return r;
        }).toList();
    }

    // ---------- 统一签退（负责人） ----------

    /**
     * 统一签退：对已签到未签退者落签退时间并算时长。volunteerIds 为空 = 全体；非空 = 仅指定。
     *
     * @return 实际签退人数
     */
    @Transactional(rollbackFor = Exception.class)
    public int bulkCheckOut(Long activityId, Long slotId, List<Long> volunteerIds, Long operatorId) {
        Activity a = requirePublished(activityId);
        ActivitySlot slot = requireSlotOfActivity(activityId, slotId);
        LocalDateTime now = LocalDateTime.now();
        // 同 checkIn：时间窗按场次（需求方 2026-07-31 拍板，理由见 checkIn 注释）
        if (slot.getEndTime() != null && now.isAfter(slot.getEndTime().plusHours(CHECKOUT_WINDOW_AFTER_HOURS))) {
            throw new BusinessException("已过签退时间（该时间段结束 2 小时内签退）");
        }
        var wrapper = Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getActivityId, activityId)
                .eq(ActivityAttendance::getSlotId, slotId)
                .isNotNull(ActivityAttendance::getCheckInTime)
                .isNull(ActivityAttendance::getCheckOutTime);
        if (volunteerIds != null && !volunteerIds.isEmpty()) {
            wrapper.in(ActivityAttendance::getVolunteerId, volunteerIds);
        }
        List<ActivityAttendance> list = attendanceMapper.selectList(wrapper);
        int count = 0;
        for (ActivityAttendance att : list) {
            // 条件更新防并发：期间该行可能已被志愿者自助签退；CAS（checkOutTime is null）失败则跳过、不覆盖其签退数据。
            // wrapper 更新不走 MetaObjectHandler 自动填充，显式 set update_time。
            int affected = attendanceMapper.update(null, Wrappers.<ActivityAttendance>lambdaUpdate()
                    .eq(ActivityAttendance::getId, att.getId())
                    .isNull(ActivityAttendance::getCheckOutTime)
                    .set(ActivityAttendance::getCheckOutTime, now)
                    .set(ActivityAttendance::getCheckOutBy, operatorId)
                    .set(ActivityAttendance::getServiceMinutes, computeMinutes(att, now))
                    .set(ActivityAttendance::getUpdateTime, now));
            if (affected > 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * 志愿者自助签退：扫「签退二维码」+ GPS 校验后调用。须本人已签到、未签退；活动须有坐标、{@code now ≤ 结束后2h}、
     * 距离 ≤ 半径。服务时长 = 签退 − 签到。GPS 仅作实时门禁不落库（无 check_out 坐标列）。
     *
     * <p>用条件更新（{@code checkOutTime is null}）CAS，affected==0 视为并发已签退——防双击 / 与负责人
     * {@link #bulkCheckOut} 互相覆盖 checkOutTime/serviceMinutes/checkOutBy。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void selfCheckOut(Long activityId, Long slotId, Long volunteerId, BigDecimal lat, BigDecimal lng) {
        Activity a = requirePublished(activityId);
        if (a.getLat() == null || a.getLng() == null) {
            throw new BusinessException("活动未设置签到坐标，无法签退");
        }
        ActivitySlot slot = requireSlotOfActivity(activityId, slotId);
        LocalDateTime now = LocalDateTime.now();
        // 同 checkIn：时间窗按场次（需求方 2026-07-31 拍板，理由见 checkIn 注释）
        if (slot.getEndTime() != null && now.isAfter(slot.getEndTime().plusHours(CHECKOUT_WINDOW_AFTER_HOURS))) {
            throw new BusinessException("已过签退时间（该时间段结束 2 小时内签退）");
        }
        requireValidCoord(lat, lng);
        int radius = a.getCheckInRadiusM() == null ? 500 : a.getCheckInRadiusM();
        double dist = distanceMeters(lat, lng, a.getLat(), a.getLng());
        if (dist > radius) {
            throw new BusinessException("您距活动地点约 " + Math.round(dist) + " 米，超出签退范围（" + radius + " 米）");
        }
        ActivityAttendance att = findAttendance(activityId, slotId, volunteerId);
        if (att == null || att.getCheckInTime() == null) {
            throw new BusinessException("您还未签到，无法签退");
        }
        if (att.getCheckOutTime() != null) {
            throw new BusinessException("您已签退");
        }
        int minutes = computeMinutes(att, now);
        int affected = attendanceMapper.update(null, Wrappers.<ActivityAttendance>lambdaUpdate()
                .eq(ActivityAttendance::getActivityId, activityId)
                .eq(ActivityAttendance::getSlotId, slotId)
                .eq(ActivityAttendance::getVolunteerId, volunteerId)
                .isNull(ActivityAttendance::getCheckOutTime)
                .isNotNull(ActivityAttendance::getCheckInTime)
                .set(ActivityAttendance::getCheckOutTime, now)
                .set(ActivityAttendance::getCheckOutBy, volunteerId)
                .set(ActivityAttendance::getServiceMinutes, minutes)
                .set(ActivityAttendance::getUpdateTime, now));
        if (affected == 0) {
            throw new BusinessException("您已签退");
        }
    }

    // ---------- 确认到家 / 双向评价 / 活动总结（第 2 批） ----------

    /**
     * 志愿者确认到家：本人所在<b>场次</b>结束后可点，记录时间与坐标。
     * 超时（结束 1h 后）不在此拒绝——仅由视图层派生标记，故此处只要求该场次已结束。
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirmHome(Long activityId, Long slotId, Long volunteerId, BigDecimal lat, BigDecimal lng) {
        Activity a = requirePublished(activityId);
        ActivitySlot slot = requireSlotOfActivity(activityId, slotId);
        if (!isSlotEnded(a, slot)) {
            throw new BusinessException("该时间段尚未结束，暂不能确认到家");
        }
        requireValidCoord(lat, lng);
        ActivityAttendance att = findAttendance(activityId, slotId, volunteerId);
        if (att == null || att.getCheckInTime() == null) {
            throw new BusinessException("您未签到该活动，无需确认到家");
        }
        att.setConfirmHomeTime(LocalDateTime.now());
        att.setConfirmHomeLat(lat);
        att.setConfirmHomeLng(lng);
        attendanceMapper.updateById(att);
    }

    /**
     * 志愿者评价活动 + 负责人：本人所在<b>场次</b>结束后、本人有考勤记录方可，
     * 写本人该场次 attendance 行的评分/留言（可覆盖）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void submitReview(Long activityId, Long slotId, Long volunteerId, Integer activityScore, Integer leaderScore, String comment) {
        Activity a = requirePublished(activityId);
        ActivitySlot slot = requireSlotOfActivity(activityId, slotId);
        if (!isSlotEnded(a, slot)) {
            throw new BusinessException("该时间段尚未结束，暂不能评价");
        }
        requireScore(activityScore, "活动评分");
        requireScore(leaderScore, "负责人评分");
        // 要求「实际签到」才能评价：负责人评价/标记请假缺席都会补建考勤行（无 check_in_time），
        // 仅判 att != null 会让未到场者也能评价，故以 check_in_time 作实际参加凭据。
        ActivityAttendance att = findAttendance(activityId, slotId, volunteerId);
        if (att == null || att.getCheckInTime() == null) {
            throw new BusinessException("您未实际参加（签到）该活动，无法评价");
        }
        att.setVolActivityScore(activityScore);
        att.setVolLeaderScore(leaderScore);
        att.setVolComment(comment);
        attendanceMapper.updateById(att);
    }

    /**
     * 负责人评价志愿者：写该志愿者 attendance 行的 leader_evaluation（无行则补建，鉴权在 controller）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void leaderEvaluate(Long activityId, Long slotId, Long volunteerId, String evaluation, Long operatorId) {
        requirePublished(activityId);
        requireApprovedEnrollmentOfSlot(activityId, slotId, volunteerId, "该志愿者未报名该时间段或报名未通过");
        ActivityAttendance att = findAttendance(activityId, slotId, volunteerId);
        if (att == null) {
            att = newAttendance(activityId, slotId, volunteerId);
            att.setLeaderEvaluation(evaluation);
            try {
                attendanceMapper.insert(att);
                return;
            } catch (DuplicateKeyException e) {
                att = findAttendance(activityId, slotId, volunteerId);
            }
        }
        att.setLeaderEvaluation(evaluation);
        attendanceMapper.updateById(att);
    }

    /**
     * 上传活动总结（负责人 /v 或管理端 /a 共用）：写 activity.summary_*。
     */
    @Transactional(rollbackFor = Exception.class)
    public void uploadSummary(Long activityId, String text, String images, Long operatorId) {
        Activity a = requirePublished(activityId);
        if (!isEnded(a)) {
            throw new BusinessException("活动尚未结束，暂不能上传总结");
        }
        a.setSummaryText(text);
        a.setSummaryImages(images);
        a.setSummaryBy(operatorId);
        a.setSummaryTime(LocalDateTime.now());
        activityMapper.updateById(a);
    }

    // ---------- 负责人视图 ----------

    /** 「我负责的活动」场次列表（leaderType=1 的志愿者负责人）。 */
    public List<ManagedActivityVO> myLedActivities(Long volunteerId) {
        List<ActivityLeader> leaders = leaderMapper.selectList(Wrappers.<ActivityLeader>lambdaQuery()
                .eq(ActivityLeader::getLeaderType, LEADER_TYPE_VOLUNTEER)
                .eq(ActivityLeader::getVolunteerId, volunteerId)
                .orderByDesc(ActivityLeader::getId));
        if (leaders.isEmpty()) {
            return List.of();
        }
        List<Long> activityIds = leaders.stream().map(ActivityLeader::getActivityId).distinct().toList();
        Map<Long, Activity> activityById = new HashMap<>();
        for (Activity a : activityMapper.selectBatchIds(activityIds)) {
            activityById.put(a.getId(), a);
        }
        return activityIds.stream().map(aid -> {
            Activity a = activityById.get(aid);
            // 跳过审核域（待审核/驳回）活动——即便有历史/脏 leader 行也不让负责人在 /v 看到未上线活动
            if (a == null || ActivityStatus.isUnderReview(a.getStatus())) {
                return null;
            }
            ManagedActivityVO vo = new ManagedActivityVO();
            vo.setActivityId(a.getId());
            vo.setSerialNo(a.getSerialNo());
            vo.setTitle(a.getTitle());
            vo.setStartTime(a.getStartTime());
            vo.setEndTime(a.getEndTime());
            vo.setRunStatus(a.getRunStatus());
            vo.setEnrolledCount(countApprovedVolunteers(aid));
            return vo;
        }).filter(java.util.Objects::nonNull).toList();
    }

    /** 负责人「活动详情」：活动概要 + 志愿者考勤名单（名字/电话/学校 + 签到签退/到位/时长/违规数）。 */
    public ManagedActivityDetailVO leaderDetail(Long activityId) {
        Activity a = activityMapper.selectById(activityId);
        // 审核域活动对负责人视图不可见（防历史/脏 leader 行泄露未上线活动详情）
        if (a == null || ActivityStatus.isUnderReview(a.getStatus())) {
            throw new BusinessException("活动不存在");
        }
        ManagedActivityDetailVO vo = new ManagedActivityDetailVO();
        vo.setActivityId(a.getId());
        vo.setSerialNo(a.getSerialNo());
        vo.setTitle(a.getTitle());
        vo.setLocation(a.getLocation());
        vo.setStartTime(a.getStartTime());
        vo.setEndTime(a.getEndTime());
        vo.setRunStatus(a.getRunStatus());
        vo.setActualStartTime(a.getActualStartTime());
        vo.setActualEndTime(a.getActualEndTime());
        vo.setRoster(buildRoster(activityId));
        vo.setEmergencyPhone(activityProperties.getEmergencyPhone());
        return vo;
    }

    // ---------- 内部辅助 ----------

    /**
     * 负责人考勤名单——<b>一行 = 一个「志愿者 × 场次」</b>（V30）。
     *
     * <p><b>需求来源</b>：xlsx Row 32 C 逐字「显示<b>活动场次</b>，活动名称，<b>活动时间段</b>，参加志愿者人数……
     * 或者负责人点击志愿者<b>是否到位</b>……<b>是否违规</b>」——负责人页本就按场次组织；
     * 原型 P15「报名详情」同一人多行、每行各带岗位时间与签到签退，且可「筛选：全部时间段」。</p>
     *
     * <p><b>旧版为什么是错的</b>：报名人按 {@code distinct()} 去重成一人一行，考勤又只按 {@code volunteerId} 索引，
     * 于是多场次活动里同一人只出一行、显示的是<b>查询顺序决定的任意一场</b>的签到签退，另一场被静默丢弃；
     * 违规数则是<b>整个活动</b>的合计，上午场没违规的人也会显示「违规 1 次」。
     * 负责人据此点「是否到位」，标的还是错的那一场。</p>
     */
    private List<AttendanceRosterVO> buildRoster(Long activityId) {
        // 名单 = 已通过的报名行；一人报两场 = 两行，不再去重
        List<ActivityEnrollment> enrolls = enrollmentMapper.selectList(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, activityId)
                .eq(ActivityEnrollment::getStatus, ENROLL_APPROVED));
        if (enrolls.isEmpty()) {
            return List.of();
        }
        List<Long> volunteerIds = enrolls.stream().map(ActivityEnrollment::getVolunteerId).distinct().toList();
        Map<Long, VolunteerDisplayView> displayById = volunteerQueryService.listDisplayByIds(volunteerIds);

        Map<Long, ActivitySlot> slotById = new HashMap<>();
        List<Long> slotIds = enrolls.stream().map(ActivityEnrollment::getSlotId)
                .filter(Objects::nonNull).distinct().toList();
        if (!slotIds.isEmpty()) {
            for (ActivitySlot s : slotMapper.selectBatchIds(slotIds)) {
                slotById.put(s.getId(), s);
            }
        }

        Map<String, ActivityAttendance> attBySlot = new HashMap<>();
        for (ActivityAttendance att : attendanceMapper.selectList(Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getActivityId, activityId)
                .in(ActivityAttendance::getVolunteerId, volunteerIds))) {
            attBySlot.put(rosterKey(att.getSlotId(), att.getVolunteerId()), att);
        }
        Map<String, Integer> violationCntBySlot = new HashMap<>();
        for (ActivityViolation v : violationMapper.selectList(Wrappers.<ActivityViolation>lambdaQuery()
                .eq(ActivityViolation::getActivityId, activityId)
                .in(ActivityViolation::getVolunteerId, volunteerIds))) {
            violationCntBySlot.merge(rosterKey(v.getSlotId(), v.getVolunteerId()), 1, Integer::sum);
        }

        return enrolls.stream()
                // 按「场次开始时间 → 志愿者 id」排，负责人现场是一个时间段一个时间段点名的
                .sorted(Comparator
                        .comparing((ActivityEnrollment e) -> {
                            ActivitySlot s = slotById.get(e.getSlotId());
                            return s == null || s.getStartTime() == null ? LocalDateTime.MAX : s.getStartTime();
                        })
                        .thenComparing(e -> e.getSlotId() == null ? Long.MAX_VALUE : e.getSlotId())
                        .thenComparing(ActivityEnrollment::getVolunteerId))
                .map(e -> {
                    Long vid = e.getVolunteerId();
                    AttendanceRosterVO r = new AttendanceRosterVO();
                    r.setVolunteerId(vid);
                    r.setSlotId(e.getSlotId());
                    ActivitySlot s = slotById.get(e.getSlotId());
                    if (s != null) {
                        r.setSlotProjectName(s.getProjectName());
                        r.setSlotStartTime(s.getStartTime());
                        r.setSlotEndTime(s.getEndTime());
                    }
                    VolunteerDisplayView d = displayById.get(vid);
                    if (d != null) {
                        r.setRealName(d.realName());
                        r.setPhone(d.phone());
                        r.setSchool(d.school());
                    }
                    ActivityAttendance att = attBySlot.get(rosterKey(e.getSlotId(), vid));
                    if (att != null) {
                        r.setCheckInTime(att.getCheckInTime());
                        r.setCheckInMethod(att.getCheckInMethod());
                        r.setCheckOutTime(att.getCheckOutTime());
                        r.setAttendStatus(att.getAttendStatus());
                        r.setServiceMinutes(att.getServiceMinutes());
                    }
                    r.setViolationCount(violationCntBySlot.getOrDefault(rosterKey(e.getSlotId(), vid), 0));
                    return r;
                }).toList();
    }

    private static String rosterKey(Long slotId, Long volunteerId) {
        return slotId + ":" + volunteerId;
    }

    /** 缺席自动记一条违规（已存在缺席违规则不重复）。 */
    private void autoAbsentViolation(Long activityId, Long slotId, Long volunteerId, Long operatorId) {
        // V30：缺席违规按【场次】判重——同一活动缺席了上午场，不代表下午场也缺席，
        // 若仍按活动判重，第二场的缺席会被当成「已记过」而漏记。
        Long exists = violationMapper.selectCount(Wrappers.<ActivityViolation>lambdaQuery()
                .eq(ActivityViolation::getActivityId, activityId)
                .eq(ActivityViolation::getSlotId, slotId)
                .eq(ActivityViolation::getVolunteerId, volunteerId)
                .eq(ActivityViolation::getViolationType, VIOLATION_ABSENT));
        if (exists != null && exists > 0) {
            return;
        }
        ActivityViolation v = new ActivityViolation();
        v.setActivityId(activityId);
        v.setSlotId(slotId);
        v.setVolunteerId(volunteerId);
        v.setViolationType(VIOLATION_ABSENT);
        v.setDescription("缺席（系统自动记录）");
        v.setRecordedBy(operatorId);
        v.setRecordedTime(LocalDateTime.now());
        violationMapper.insert(v);
    }

    /** 服务时长（分钟）：请假/缺席记 0；否则 签退−签到，负数兜底为 0。 */
    private int computeMinutes(ActivityAttendance att, LocalDateTime checkOut) {
        Integer st = att.getAttendStatus();
        if (Integer.valueOf(ATTEND_LEAVE).equals(st) || Integer.valueOf(ATTEND_ABSENT).equals(st)) {
            return 0;
        }
        if (att.getCheckInTime() == null) {
            return 0;
        }
        long minutes = Duration.between(att.getCheckInTime(), checkOut).toMinutes();
        return minutes < 0 ? 0 : (int) minutes;
    }

    private ActivityAttendance newAttendance(Long activityId, Long slotId, Long volunteerId) {
        ActivityAttendance att = new ActivityAttendance();
        att.setActivityId(activityId);
        att.setSlotId(slotId);
        att.setVolunteerId(volunteerId);
        att.setSecretaryStatus(0);
        att.setPointsStatus(0);
        att.setPointsFactor(0);
        return att;
    }

    private ActivityAttendance findAttendance(Long activityId, Long slotId, Long volunteerId) {
        return attendanceMapper.selectOne(Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getActivityId, activityId)
                .eq(ActivityAttendance::getSlotId, slotId)
                .eq(ActivityAttendance::getVolunteerId, volunteerId)
                .last("limit 1"));
    }

    /**
     * 取本活动下的场次并校验归属。
     *
     * <p>场次必须属于该活动——否则传一个别的活动的 slotId 就能在本活动下建出一条
     * 指向他人场次的考勤，唯一键也拦不住（它只保证 activity+slot+volunteer 组合不重复）。</p>
     */
    private ActivitySlot requireSlotOfActivity(Long activityId, Long slotId) {
        if (slotId == null) {
            throw new BusinessException("请选择活动时间段");
        }
        ActivitySlot slot = slotMapper.selectById(slotId);
        if (slot == null || !activityId.equals(slot.getActivityId())) {
            throw new BusinessException("活动时间段不存在");
        }
        return slot;
    }

    /**
     * 报名校验下沉到场次：必须报了<b>这一场</b>且已通过。
     *
     * <p>依据原型 P15——报名详情每一行就是「一个报名场次 + 该场次的签到签退」，
     * 报了上午场的人不应该能签下午场的到。</p>
     */
    private void requireApprovedEnrollmentOfSlot(Long activityId, Long slotId, Long volunteerId, String message) {
        Long c = enrollmentMapper.selectCount(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, activityId)
                .eq(ActivityEnrollment::getSlotId, slotId)
                .eq(ActivityEnrollment::getVolunteerId, volunteerId)
                .eq(ActivityEnrollment::getStatus, ENROLL_APPROVED));
        if (c == null || c == 0) {
            throw new BusinessException(message);
        }
    }

    private Activity requirePublished(Long activityId) {
        Activity a = activityMapper.selectById(activityId);
        if (a == null || !Integer.valueOf(ACTIVITY_PUBLISHED).equals(a.getStatus())) {
            throw new BusinessException("活动不存在");
        }
        return a;
    }

    /**
     * 整个活动是否已结束：负责人点过结束（run_status=2）或已过活动 end_time。
     *
     * <p>仅用于<b>活动级</b>动作（上传总结）。志愿者个人的「确认到家 / 评价」按场次判，见 {@link #isSlotEnded}。</p>
     */
    private boolean isEnded(Activity a) {
        if (Integer.valueOf(RUN_ENDED).equals(a.getRunStatus())) {
            return true;
        }
        return a.getEndTime() != null && LocalDateTime.now().isAfter(a.getEndTime());
    }

    /**
     * 某人「这一场」是否已结束——确认到家 / 评价的开门条件。
     *
     * <p><b>需求依据</b>：xlsx Row 32 C「活动结束后点击活动结束，在<b>活动结束一小时内</b>，志愿者需要点击确认到家，
     * 可对志愿者进行评价」。「活动结束」有两条到达路径，本方法都认：</p>
     * <ol>
     *   <li><b>负责人点「活动结束」</b>（{@code run_status=2}）——负责人是<b>活动级</b>的
     *       （Row 32 D 未提按场次指派，故 {@code activity_leader} 保持活动粒度），他点一次即整个活动结束，所有场次一并结束；</li>
     *   <li><b>自然过点</b>——按<b>本场次</b>的 {@code end_time}，不是活动整体 {@code end_time}。
     *       依据 2026-07-31 拍板的「时间窗按场次」：9:00–18:00 的活动里，上午 9:00–12:00 那场的人
     *       12 点就该能确认到家，不该被扣到下午场散场之后；「结束一小时内」对他而言也才有意义。</li>
     * </ol>
     *
     * <p>场次未设 {@code end_time} 时退回活动 {@code end_time}，与旧行为一致。</p>
     */
    private boolean isSlotEnded(Activity a, ActivitySlot slot) {
        if (Integer.valueOf(RUN_ENDED).equals(a.getRunStatus())) {
            return true;
        }
        LocalDateTime end = slot.getEndTime() != null ? slot.getEndTime() : a.getEndTime();
        return end != null && LocalDateTime.now().isAfter(end);
    }

    /** 评分范围守卫：必须 1~5。 */
    private void requireScore(Integer score, String label) {
        if (score == null || score < 1 || score > 5) {
            throw new BusinessException(label + "范围 1~5");
        }
    }

    private void requireApprovedEnrollment(Long activityId, Long volunteerId, String message) {
        Long c = enrollmentMapper.selectCount(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, activityId)
                .eq(ActivityEnrollment::getVolunteerId, volunteerId)
                .eq(ActivityEnrollment::getStatus, ENROLL_APPROVED));
        if (c == null || c == 0) {
            throw new BusinessException(message);
        }
    }

    private long countApprovedVolunteers(Long activityId) {
        List<ActivityEnrollment> enrolls = enrollmentMapper.selectList(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, activityId)
                .eq(ActivityEnrollment::getStatus, ENROLL_APPROVED));
        return enrolls.stream().map(ActivityEnrollment::getVolunteerId).distinct().count();
    }

    /** 经纬度范围守卫：纬度 [-90,90]、经度 [-180,180]，否则拒绝（防三角函数周期性绕过半径）。 */
    private void requireValidCoord(BigDecimal lat, BigDecimal lng) {
        if (lat == null || lng == null
                || lat.doubleValue() < -90 || lat.doubleValue() > 90
                || lng.doubleValue() < -180 || lng.doubleValue() > 180) {
            throw new BusinessException("签到坐标非法");
        }
    }

    /** Haversine 距离（米）。 */
    private double distanceMeters(BigDecimal lat1, BigDecimal lng1, BigDecimal lat2, BigDecimal lng2) {
        double rlat1 = Math.toRadians(lat1.doubleValue());
        double rlat2 = Math.toRadians(lat2.doubleValue());
        double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLng = Math.toRadians(lng2.doubleValue() - lng1.doubleValue());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(rlat1) * Math.cos(rlat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }
}
