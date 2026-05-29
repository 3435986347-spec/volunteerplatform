package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.vo.ServiceRecordVO;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerDisplayView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 服务记录：志愿者「我的服务记录」、管理端「服务记录大板块」、秘书部确认时长、积分发放。
 *
 * <p>闭环次序：统一签退（有 check_out_time）→ 秘书部确认（secretary_status=1）→ 积分发放（points_status=1）。
 * 确认 / 发放都用 CAS 条件更新保原子，避免并发重复确认 / 重复发分。</p>
 *
 * <p>积分 = 基数 × 角色倍率 × 违规系数：现场负责人 ×1.4、普通志愿者 ×1.0（管理团队 ×1.2 待
 * 「参与者=管理团队」数据通道，本批先按 ×1.0）；请假 / 缺席记 0；违规系数 正常1 / 减半0.5 / 不发0。</p>
 *
 * @author hengde
 */
@Service
public class ServiceRecordService {

    private static final int ATTEND_LEAVE = 2;
    private static final int ATTEND_ABSENT = 4;

    private static final int SECRETARY_PENDING = 0;
    private static final int SECRETARY_CONFIRMED = 1;
    private static final int POINTS_NOT_GRANTED = 0;
    private static final int POINTS_GRANTED = 1;

    private static final int FACTOR_NORMAL = 0;
    private static final int FACTOR_HALF = 1;
    private static final int FACTOR_NONE = 2;

    private ActivityAttendanceMapper attendanceMapper;
    private ActivityMapper activityMapper;
    private VolunteerQueryService volunteerQueryService;
    private ActivityLeaderService activityLeaderService;

    @Autowired
    public void setAttendanceMapper(ActivityAttendanceMapper attendanceMapper) {
        this.attendanceMapper = attendanceMapper;
    }

    @Autowired
    public void setActivityMapper(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setActivityLeaderService(ActivityLeaderService activityLeaderService) {
        this.activityLeaderService = activityLeaderService;
    }

    // ---------- 查询 ----------

    /** 志愿者端「我的服务记录」：本人考勤记录 + 活动名称 + 签到/签退/时长，按签到时间倒序。 */
    public PageResult<ServiceRecordVO> myRecords(PageQuery query, Long volunteerId) {
        Page<ActivityAttendance> page = query.toPage();
        attendanceMapper.selectPage(page, Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getVolunteerId, volunteerId)
                .orderByDesc(ActivityAttendance::getCheckInTime)
                .orderByDesc(ActivityAttendance::getId));
        return toVoPage(page, false);
    }

    /** 管理端「服务记录大板块」：全员，可按活动/志愿者/秘书确认状态筛选，带姓名与活动名。 */
    public PageResult<ServiceRecordVO> board(PageQuery query, Long activityId, Long volunteerId, Integer secretaryStatus) {
        Page<ActivityAttendance> page = query.toPage();
        var wrapper = Wrappers.<ActivityAttendance>lambdaQuery();
        if (activityId != null) {
            wrapper.eq(ActivityAttendance::getActivityId, activityId);
        }
        if (volunteerId != null) {
            wrapper.eq(ActivityAttendance::getVolunteerId, volunteerId);
        }
        if (secretaryStatus != null) {
            wrapper.eq(ActivityAttendance::getSecretaryStatus, secretaryStatus);
        }
        wrapper.orderByDesc(ActivityAttendance::getId);
        attendanceMapper.selectPage(page, wrapper);
        return toVoPage(page, true);
    }

    /** 管理端「待秘书部确认」：已签退(check_out_time 非空)且未确认。 */
    public PageResult<ServiceRecordVO> pendingConfirm(PageQuery query) {
        Page<ActivityAttendance> page = query.toPage();
        attendanceMapper.selectPage(page, Wrappers.<ActivityAttendance>lambdaQuery()
                .isNotNull(ActivityAttendance::getCheckOutTime)
                .eq(ActivityAttendance::getSecretaryStatus, SECRETARY_PENDING)
                .orderByDesc(ActivityAttendance::getId));
        return toVoPage(page, true);
    }

    // ---------- 秘书部确认 / 积分发放 ----------

    /** 秘书部确认时长：要求已签退且未确认。CAS 保原子。 */
    @Transactional(rollbackFor = Exception.class)
    public void secretaryConfirm(Long attendanceId, Long adminId) {
        LocalDateTime now = LocalDateTime.now();
        int rows = attendanceMapper.update(null, Wrappers.<ActivityAttendance>lambdaUpdate()
                .set(ActivityAttendance::getSecretaryStatus, SECRETARY_CONFIRMED)
                .set(ActivityAttendance::getSecretaryBy, adminId)
                .set(ActivityAttendance::getSecretaryTime, now)
                .set(ActivityAttendance::getUpdateTime, now)
                .eq(ActivityAttendance::getId, attendanceId)
                .eq(ActivityAttendance::getSecretaryStatus, SECRETARY_PENDING)
                .isNotNull(ActivityAttendance::getCheckOutTime));
        if (rows != 1) {
            throw new BusinessException("记录不存在、未签退或已确认");
        }
    }

    /**
     * 积分发放：要求已秘书确认且未发放。按 基数×角色倍率×违规系数 算实发，CAS 落库。
     *
     * @param pointsFactor 违规调整 0正常/1减半/2不发（默认 0）
     */
    @Transactional(rollbackFor = Exception.class)
    public int grantPoints(Long attendanceId, Integer pointsFactor, Long adminId) {
        ActivityAttendance att = attendanceMapper.selectById(attendanceId);
        if (att == null) {
            throw new BusinessException("考勤记录不存在");
        }
        if (!Integer.valueOf(SECRETARY_CONFIRMED).equals(att.getSecretaryStatus())) {
            throw new BusinessException("请先由秘书部确认时长再发放积分");
        }
        if (Integer.valueOf(POINTS_GRANTED).equals(att.getPointsStatus())) {
            throw new BusinessException("该记录积分已发放");
        }
        int factor = pointsFactor == null ? FACTOR_NORMAL : pointsFactor;
        if (factor < FACTOR_NORMAL || factor > FACTOR_NONE) {
            throw new BusinessException("积分调整非法（0正常/1减半/2不发）");
        }
        Activity activity = activityMapper.selectById(att.getActivityId());
        if (activity == null) {
            throw new BusinessException("活动不存在");
        }
        int award = computePoints(activity, att, factor);

        LocalDateTime now = LocalDateTime.now();
        int rows = attendanceMapper.update(null, Wrappers.<ActivityAttendance>lambdaUpdate()
                .set(ActivityAttendance::getPointsAward, award)
                .set(ActivityAttendance::getPointsFactor, factor)
                .set(ActivityAttendance::getPointsStatus, POINTS_GRANTED)
                .set(ActivityAttendance::getUpdateTime, now)
                .eq(ActivityAttendance::getId, attendanceId)
                .eq(ActivityAttendance::getPointsStatus, POINTS_NOT_GRANTED)
                .eq(ActivityAttendance::getSecretaryStatus, SECRETARY_CONFIRMED));
        if (rows != 1) {
            throw new BusinessException("积分已发放或状态变更，请刷新重试");
        }
        return award;
    }

    // ---------- 内部 ----------

    /** 积分 = 基数 × 角色倍率 × 违规系数；请假/缺席记 0。 */
    private int computePoints(Activity activity, ActivityAttendance att, int factor) {
        Integer st = att.getAttendStatus();
        if (Integer.valueOf(ATTEND_LEAVE).equals(st) || Integer.valueOf(ATTEND_ABSENT).equals(st)) {
            return 0;
        }
        int base = activity.getPointsBase() == null ? 0 : activity.getPointsBase();
        // 角色倍率：现场负责人 ×1.4 优先于 管理团队 ×1.2，普通 ×1.0
        double roleMult = 1.0;
        if (activityLeaderService.isVolunteerLeader(att.getActivityId(), att.getVolunteerId())) {
            BigDecimal lm = activity.getLeaderMultiplier();
            roleMult = lm == null ? 1.4 : lm.doubleValue();
        } else if (volunteerQueryService.isManager(att.getVolunteerId())) {
            BigDecimal mm = activity.getManagerMultiplier();
            roleMult = mm == null ? 1.2 : mm.doubleValue();
        }
        double factorMult = switch (factor) {
            case FACTOR_HALF -> 0.5;
            case FACTOR_NONE -> 0.0;
            default -> 1.0;
        };
        return (int) Math.round(base * roleMult * factorMult);
    }

    private PageResult<ServiceRecordVO> toVoPage(Page<ActivityAttendance> page, boolean withVolunteerName) {
        List<ActivityAttendance> records = page.getRecords();
        Map<Long, Activity> activityById = batchActivities(records);
        Map<Long, VolunteerDisplayView> displayById = withVolunteerName ? batchDisplays(records) : Map.of();
        List<ServiceRecordVO> vos = records.stream().map(att -> {
            ServiceRecordVO vo = new ServiceRecordVO();
            vo.setAttendanceId(att.getId());
            vo.setActivityId(att.getActivityId());
            vo.setVolunteerId(att.getVolunteerId());
            vo.setCheckInTime(att.getCheckInTime());
            vo.setCheckOutTime(att.getCheckOutTime());
            vo.setServiceMinutes(att.getServiceMinutes());
            vo.setAttendStatus(att.getAttendStatus());
            vo.setSecretaryStatus(att.getSecretaryStatus());
            vo.setPointsAward(att.getPointsAward());
            vo.setPointsStatus(att.getPointsStatus());
            Activity a = activityById.get(att.getActivityId());
            if (a != null) {
                vo.setSerialNo(a.getSerialNo());
                vo.setActivityTitle(a.getTitle());
            }
            if (withVolunteerName) {
                VolunteerDisplayView d = displayById.get(att.getVolunteerId());
                if (d != null) {
                    vo.setVolunteerName(d.realName());
                }
            }
            return vo;
        }).toList();
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }

    private Map<Long, Activity> batchActivities(List<ActivityAttendance> records) {
        List<Long> ids = records.stream().map(ActivityAttendance::getActivityId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return activityMapper.selectBatchIds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(Activity::getId, java.util.function.Function.identity()));
    }

    private Map<Long, VolunteerDisplayView> batchDisplays(List<ActivityAttendance> records) {
        List<Long> ids = records.stream().map(ActivityAttendance::getVolunteerId).distinct().toList();
        return volunteerQueryService.listDisplayByIds(ids);
    }
}
