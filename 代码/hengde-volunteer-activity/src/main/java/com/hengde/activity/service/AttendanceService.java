package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dao.ActivityLeaderMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivityViolationMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.ActivityEnrollment;
import com.hengde.activity.entity.ActivityLeader;
import com.hengde.activity.entity.ActivityViolation;
import com.hengde.activity.vo.AttendanceRosterVO;
import com.hengde.activity.vo.ManagedActivityDetailVO;
import com.hengde.activity.vo.ManagedActivityVO;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerDisplayView;
import com.hengde.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private static final int ACTIVITY_PUBLISHED = 1;
    private static final int ENROLL_APPROVED = 1;

    private static final int RUN_NOT_STARTED = 0;
    private static final int RUN_RUNNING = 1;
    private static final int RUN_ENDED = 2;

    private static final int ATTEND_NORMAL = 1;
    private static final int ATTEND_LEAVE = 2;
    private static final int ATTEND_LATE = 3;
    private static final int ATTEND_ABSENT = 4;

    private static final int CHECKIN_SCAN = 1;
    private static final int CHECKIN_AUTO = 2;
    private static final int CHECKIN_LEADER = 3;

    private static final int VIOLATION_ABSENT = 5;

    private static final long CHECKIN_OPEN_BEFORE_HOURS = 2;
    private static final long CHECKOUT_WINDOW_AFTER_HOURS = 2;

    private static final double EARTH_RADIUS_M = 6_371_000;

    private static final int LEADER_TYPE_VOLUNTEER = 1;

    private ActivityMapper activityMapper;
    private ActivityAttendanceMapper attendanceMapper;
    private ActivityViolationMapper violationMapper;
    private ActivityEnrollmentMapper enrollmentMapper;
    private ActivityLeaderMapper leaderMapper;
    private VolunteerQueryService volunteerQueryService;

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

    // ---------- 签到（志愿者自助 GPS） ----------

    /**
     * 志愿者自助签到：校验报名(已通过) + 时间窗口 + GPS 距离 ≤ 半径，落 check_in_*。重复签到拒绝。
     */
    @Transactional(rollbackFor = Exception.class)
    public void checkIn(Long activityId, Long volunteerId, BigDecimal lat, BigDecimal lng, Integer method) {
        Activity a = requirePublished(activityId);
        if (a.getLat() == null || a.getLng() == null) {
            throw new BusinessException("活动未设置签到坐标，无法签到");
        }
        LocalDateTime now = LocalDateTime.now();
        if (a.getStartTime() != null && now.isBefore(a.getStartTime().minusHours(CHECKIN_OPEN_BEFORE_HOURS))) {
            throw new BusinessException("未到签到时间（活动开始前 2 小时开放签到）");
        }
        if (a.getEndTime() != null && now.isAfter(a.getEndTime().plusHours(CHECKOUT_WINDOW_AFTER_HOURS))) {
            throw new BusinessException("签到已截止");
        }
        requireApprovedEnrollment(activityId, volunteerId, "您未报名该活动或报名未通过，无法签到");

        int radius = a.getCheckInRadiusM() == null ? 500 : a.getCheckInRadiusM();
        double dist = distanceMeters(lat, lng, a.getLat(), a.getLng());
        if (dist > radius) {
            throw new BusinessException("您距活动地点约 " + Math.round(dist) + " 米，超出签到范围（" + radius + " 米）");
        }
        int m = Integer.valueOf(CHECKIN_SCAN).equals(method) ? CHECKIN_SCAN : CHECKIN_AUTO;

        ActivityAttendance att = findAttendance(activityId, volunteerId);
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
        att = newAttendance(activityId, volunteerId);
        att.setCheckInTime(now);
        att.setCheckInMethod(m);
        att.setCheckInBy(volunteerId);
        att.setCheckInLat(lat);
        att.setCheckInLng(lng);
        att.setAttendStatus(ATTEND_NORMAL);
        try {
            attendanceMapper.insert(att);
        } catch (DuplicateKeyException e) {
            // uk_activity_volunteer：并发重复签到
            throw new BusinessException("您已签到");
        }
    }

    // ---------- 到位状态 / 违规（负责人） ----------

    /** 标记到位状态：缺席 → 时长 0 + 自动违规；请假 → 时长 0；正常/迟到 → 时长留待签退算。 */
    @Transactional(rollbackFor = Exception.class)
    public void markAttendStatus(Long activityId, Long volunteerId, Integer status, Long operatorId) {
        if (status == null || status < ATTEND_NORMAL || status > ATTEND_ABSENT) {
            throw new BusinessException("到位状态非法（1正常/2请假/3迟到/4缺席）");
        }
        requirePublished(activityId);
        requireApprovedEnrollment(activityId, volunteerId, "该志愿者未报名或报名未通过");

        ActivityAttendance att = findAttendance(activityId, volunteerId);
        boolean isNew = att == null;
        if (isNew) {
            att = newAttendance(activityId, volunteerId);
        }
        att.setAttendStatus(status);
        if (Integer.valueOf(ATTEND_ABSENT).equals(status) || Integer.valueOf(ATTEND_LEAVE).equals(status)) {
            att.setServiceMinutes(0);
        }
        if (isNew) {
            try {
                attendanceMapper.insert(att);
            } catch (DuplicateKeyException e) {
                att = findAttendance(activityId, volunteerId);
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
            autoAbsentViolation(activityId, volunteerId, operatorId);
        }
    }

    /** 负责人记录违规，返回违规记录 id。 */
    @Transactional(rollbackFor = Exception.class)
    public Long recordViolation(Long activityId, Long volunteerId, Integer type, String description, Long operatorId) {
        requirePublished(activityId);
        requireApprovedEnrollment(activityId, volunteerId, "该志愿者未报名或报名未通过");
        ActivityViolation v = new ActivityViolation();
        v.setActivityId(activityId);
        v.setVolunteerId(volunteerId);
        v.setViolationType(type == null ? 0 : type);
        v.setDescription(description);
        v.setRecordedBy(operatorId);
        v.setRecordedTime(LocalDateTime.now());
        violationMapper.insert(v);
        return v.getId();
    }

    // ---------- 统一签退（负责人） ----------

    /**
     * 统一签退：对已签到未签退者落签退时间并算时长。volunteerIds 为空 = 全体；非空 = 仅指定。
     *
     * @return 实际签退人数
     */
    @Transactional(rollbackFor = Exception.class)
    public int bulkCheckOut(Long activityId, List<Long> volunteerIds, Long operatorId) {
        Activity a = requirePublished(activityId);
        LocalDateTime now = LocalDateTime.now();
        if (a.getEndTime() != null && now.isAfter(a.getEndTime().plusHours(CHECKOUT_WINDOW_AFTER_HOURS))) {
            throw new BusinessException("已过签退时间（活动结束 2 小时内签退）");
        }
        var wrapper = Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getActivityId, activityId)
                .isNotNull(ActivityAttendance::getCheckInTime)
                .isNull(ActivityAttendance::getCheckOutTime);
        if (volunteerIds != null && !volunteerIds.isEmpty()) {
            wrapper.in(ActivityAttendance::getVolunteerId, volunteerIds);
        }
        List<ActivityAttendance> list = attendanceMapper.selectList(wrapper);
        int count = 0;
        for (ActivityAttendance att : list) {
            att.setCheckOutTime(now);
            att.setCheckOutBy(operatorId);
            att.setServiceMinutes(computeMinutes(att, now));
            attendanceMapper.updateById(att);
            count++;
        }
        return count;
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
            if (a == null) {
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
        if (a == null) {
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
        return vo;
    }

    // ---------- 内部辅助 ----------

    private List<AttendanceRosterVO> buildRoster(Long activityId) {
        // 名单 = 已通过报名的志愿者（去重）
        List<ActivityEnrollment> enrolls = enrollmentMapper.selectList(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, activityId)
                .eq(ActivityEnrollment::getStatus, ENROLL_APPROVED));
        List<Long> volunteerIds = enrolls.stream().map(ActivityEnrollment::getVolunteerId).distinct().toList();
        if (volunteerIds.isEmpty()) {
            return List.of();
        }
        Map<Long, VolunteerDisplayView> displayById = volunteerQueryService.listDisplayByIds(volunteerIds);
        Map<Long, ActivityAttendance> attByVol = new HashMap<>();
        for (ActivityAttendance att : attendanceMapper.selectList(Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getActivityId, activityId)
                .in(ActivityAttendance::getVolunteerId, volunteerIds))) {
            attByVol.put(att.getVolunteerId(), att);
        }
        Map<Long, Integer> violationCntByVol = new HashMap<>();
        for (ActivityViolation v : violationMapper.selectList(Wrappers.<ActivityViolation>lambdaQuery()
                .eq(ActivityViolation::getActivityId, activityId)
                .in(ActivityViolation::getVolunteerId, volunteerIds))) {
            violationCntByVol.merge(v.getVolunteerId(), 1, Integer::sum);
        }
        return volunteerIds.stream().map(vid -> {
            AttendanceRosterVO r = new AttendanceRosterVO();
            r.setVolunteerId(vid);
            VolunteerDisplayView d = displayById.get(vid);
            if (d != null) {
                r.setRealName(d.realName());
                r.setPhone(d.phone());
                r.setSchool(d.school());
            }
            ActivityAttendance att = attByVol.get(vid);
            if (att != null) {
                r.setCheckInTime(att.getCheckInTime());
                r.setCheckInMethod(att.getCheckInMethod());
                r.setCheckOutTime(att.getCheckOutTime());
                r.setAttendStatus(att.getAttendStatus());
                r.setServiceMinutes(att.getServiceMinutes());
            }
            r.setViolationCount(violationCntByVol.getOrDefault(vid, 0));
            return r;
        }).toList();
    }

    /** 缺席自动记一条违规（已存在缺席违规则不重复）。 */
    private void autoAbsentViolation(Long activityId, Long volunteerId, Long operatorId) {
        Long exists = violationMapper.selectCount(Wrappers.<ActivityViolation>lambdaQuery()
                .eq(ActivityViolation::getActivityId, activityId)
                .eq(ActivityViolation::getVolunteerId, volunteerId)
                .eq(ActivityViolation::getViolationType, VIOLATION_ABSENT));
        if (exists != null && exists > 0) {
            return;
        }
        ActivityViolation v = new ActivityViolation();
        v.setActivityId(activityId);
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

    private ActivityAttendance newAttendance(Long activityId, Long volunteerId) {
        ActivityAttendance att = new ActivityAttendance();
        att.setActivityId(activityId);
        att.setVolunteerId(volunteerId);
        att.setSecretaryStatus(0);
        att.setPointsStatus(0);
        att.setPointsFactor(0);
        return att;
    }

    private ActivityAttendance findAttendance(Long activityId, Long volunteerId) {
        return attendanceMapper.selectOne(Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getActivityId, activityId)
                .eq(ActivityAttendance::getVolunteerId, volunteerId)
                .last("limit 1"));
    }

    private Activity requirePublished(Long activityId) {
        Activity a = activityMapper.selectById(activityId);
        if (a == null || !Integer.valueOf(ACTIVITY_PUBLISHED).equals(a.getStatus())) {
            throw new BusinessException("活动不存在");
        }
        return a;
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
