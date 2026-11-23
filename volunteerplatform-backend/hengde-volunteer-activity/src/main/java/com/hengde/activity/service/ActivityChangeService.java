package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengde.activity.constant.AttendStatus;
import com.hengde.activity.constant.AuditStatus;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.constant.PointsStatus;
import com.hengde.activity.dao.ActivityAttendanceChangeMapper;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.ActivityAttendanceChange;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.vo.AttendanceChangeVO;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 考勤/积分变更二次审核（V14，第 2 批·PR2）：组织部申请改签到/签退/积分 → 不立即生效，
 * 部长二次审核通过后才应用到 {@link ActivityAttendance}。
 *
 * <p>申请/审核的角色门控在 controller（{@code attendance-edit}/{@code attendance-audit}）。
 * 审核通过/拒绝均用 CAS 条件更新（status 0→1 / 0→2）保原子，防并发重复审核；通过后在同一事务内应用变更，
 * 应用失败则整体回滚（连审核状态一并回滚）。改签到/签退会按 签退−签到 重算 {@code service_minutes}
 * （请假/缺席记 0，缺一边时长保持原值）；改积分直接覆盖 {@code points_award}。</p>
 *
 * <p><b>改积分前置：</b>仅当该考勤 {@code points_status=已发放} 才允许申请改积分——否则随后的
 * {@code ServiceRecordService.grantPoints()} 仍会按公式重算并覆盖本次修正（其 CAS 条件是 {@code points_status=未发放}），
 * 已审核通过的修正会被静默冲掉。改签到/签退不受此限：积分基于 {@code points_base} 计算，与时长解耦。</p>
 *
 * @author hengde
 */
@Service
public class ActivityChangeService {

    private static final int CHANGE_CHECKIN = 1;
    private static final int CHANGE_CHECKOUT = 2;
    private static final int CHANGE_POINTS = 3;

    private static final int STATUS_PENDING = AuditStatus.PENDING;
    private static final int STATUS_APPROVED = AuditStatus.APPROVED;
    private static final int STATUS_REJECTED = AuditStatus.REJECTED;

    private static final int ATTEND_LEAVE = AttendStatus.LEAVE;
    private static final int ATTEND_ABSENT = AttendStatus.ABSENT;

    /** 积分发放状态：已发放（与 {@code ServiceRecordService.POINTS_GRANTED} 对齐）。 */
    private static final int POINTS_GRANTED = PointsStatus.GRANTED;

    private ActivityAttendanceChangeMapper changeMapper;
    private ActivityAttendanceMapper attendanceMapper;
    private ActivityMapper activityMapper;
    private ActivitySlotMapper slotMapper;
    private VolunteerQueryService volunteerQueryService;
    private PointService pointService;

    @Autowired
    public void setChangeMapper(ActivityAttendanceChangeMapper changeMapper) {
        this.changeMapper = changeMapper;
    }

    @Autowired
    public void setSlotMapper(ActivitySlotMapper slotMapper) {
        this.slotMapper = slotMapper;
    }

    @Autowired
    public void setPointService(PointService pointService) {
        this.pointService = pointService;
    }

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

    /**
     * 组织部申请变更（不立即生效）：快照原值 + 校验新值格式，落一条待审记录。
     *
     * @return 新建变更记录 id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long requestChange(Long attendanceId, Integer changeType, String newValue, String reason, Long requesterId) {
        if (requesterId == null) {
            throw new BusinessException("操作人不能为空");
        }
        if (!StringUtils.hasText(newValue)) {
            throw new BusinessException("新值不能为空");
        }
        ActivityAttendance att = attendanceMapper.selectById(attendanceId);
        if (att == null) {
            throw new BusinessException("考勤记录不存在");
        }
        String oldValue = snapshotOldValue(att, changeType);   // 同时校验 changeType 合法
        validateNewValue(changeType, newValue);
        // 改积分只允许在积分「已发放」后申请：否则后续 grantPoints() 仍会按公式重算并覆盖掉本次修正（CAS 要求 points_status=未发放）。
        if (Integer.valueOf(CHANGE_POINTS).equals(changeType)
                && !Integer.valueOf(POINTS_GRANTED).equals(att.getPointsStatus())) {
            throw new BusinessException("积分尚未发放，无法申请修改积分");
        }

        ActivityAttendanceChange ch = new ActivityAttendanceChange();
        ch.setAttendanceId(attendanceId);
        ch.setChangeType(changeType);
        ch.setOldValue(oldValue);
        ch.setNewValue(newValue);
        ch.setReason(reason);
        ch.setStatus(STATUS_PENDING);
        ch.setRequestedBy(requesterId);
        ch.setRequestedTime(LocalDateTime.now());
        changeMapper.insert(ch);
        return ch.getId();
    }

    /** 变更申请列表（可按状态筛选），带出所属活动/志愿者上下文。 */
    public PageResult<AttendanceChangeVO> list(PageQuery query, Integer status) {
        Page<ActivityAttendanceChange> page = query.toPage();
        var wrapper = Wrappers.<ActivityAttendanceChange>lambdaQuery();
        if (status != null) {
            wrapper.eq(ActivityAttendanceChange::getStatus, status);
        }
        wrapper.orderByDesc(ActivityAttendanceChange::getId);
        changeMapper.selectPage(page, wrapper);

        List<Long> attIds = page.getRecords().stream()
                .map(ActivityAttendanceChange::getAttendanceId).distinct().toList();
        Map<Long, ActivityAttendance> attById = attIds.isEmpty() ? Map.of()
                : attendanceMapper.selectBatchIds(attIds).stream()
                .collect(Collectors.toMap(ActivityAttendance::getId, Function.identity()));

        // 带出活动标题 + 志愿者姓名（供审核者识别，避免只显示 id）
        List<Long> activityIds = attById.values().stream().map(ActivityAttendance::getActivityId).filter(Objects::nonNull).distinct().toList();
        List<Long> volunteerIds = attById.values().stream().map(ActivityAttendance::getVolunteerId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> titleById = activityIds.isEmpty() ? Map.of()
                : activityMapper.selectBatchIds(activityIds).stream()
                .collect(Collectors.toMap(Activity::getId, Activity::getTitle));
        Map<Long, String> nameById = volunteerIds.isEmpty() ? Map.of()
                : volunteerQueryService.listNamesByIds(volunteerIds);
        // V30：带出场次——既区分同一人同活动的多场申请，也给审核者提供「新值是否合理」的参照
        List<Long> slotIds = attById.values().stream().map(ActivityAttendance::getSlotId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, ActivitySlot> slotById = slotIds.isEmpty() ? Map.of()
                : slotMapper.selectBatchIds(slotIds).stream()
                .collect(Collectors.toMap(ActivitySlot::getId, Function.identity()));

        List<AttendanceChangeVO> vos = page.getRecords().stream()
                .map(ch -> toVo(ch, attById.get(ch.getAttendanceId()), titleById, nameById, slotById)).toList();
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 部长二次审核通过：CAS 待审→通过，并在同一事务内应用变更到考勤行。
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long changeId, String auditReason, Long auditorId) {
        if (auditorId == null) {
            throw new BusinessException("审核人不能为空");
        }
        ActivityAttendanceChange ch = changeMapper.selectById(changeId);
        if (ch == null || !Integer.valueOf(STATUS_PENDING).equals(ch.getStatus())) {
            throw new BusinessException("变更申请不存在或已审核");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = changeMapper.update(null, Wrappers.<ActivityAttendanceChange>lambdaUpdate()
                .set(ActivityAttendanceChange::getStatus, STATUS_APPROVED)
                .set(ActivityAttendanceChange::getAuditedBy, auditorId)
                .set(ActivityAttendanceChange::getAuditedTime, now)
                .set(ActivityAttendanceChange::getAuditReason, auditReason)
                .set(ActivityAttendanceChange::getUpdateTime, now)
                .eq(ActivityAttendanceChange::getId, changeId)
                .eq(ActivityAttendanceChange::getStatus, STATUS_PENDING));
        if (rows != 1) {
            throw new BusinessException("变更申请已被处理，请刷新重试");
        }
        // 显式传 auditorId：ch 是 CAS 之前读出的快照，其 auditedBy 仍为 null，不能用于积分流水的操作人
        applyChange(ch, auditorId);
    }

    /** 部长二次审核拒绝：CAS 待审→拒绝，不应用变更。 */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long changeId, String auditReason, Long auditorId) {
        if (auditorId == null) {
            throw new BusinessException("审核人不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = changeMapper.update(null, Wrappers.<ActivityAttendanceChange>lambdaUpdate()
                .set(ActivityAttendanceChange::getStatus, STATUS_REJECTED)
                .set(ActivityAttendanceChange::getAuditedBy, auditorId)
                .set(ActivityAttendanceChange::getAuditedTime, now)
                .set(ActivityAttendanceChange::getAuditReason, auditReason)
                .set(ActivityAttendanceChange::getUpdateTime, now)
                .eq(ActivityAttendanceChange::getId, changeId)
                .eq(ActivityAttendanceChange::getStatus, STATUS_PENDING));
        if (rows != 1) {
            throw new BusinessException("变更申请不存在或已处理");
        }
    }

    // ---------- 内部 ----------

    /**
     * 应用已通过的变更。<b>必须在 {@code approve} 的事务内调用</b>——它对考勤行加了排他锁。
     *
     * <p>取考勤用 {@code FOR UPDATE} 而非普通查询，是因为同一条考勤可以同时挂多张待审申请，
     * 各自审核时 CAS 的是<b>各自的申请行</b>，互不冲突，串行化不了对考勤的读改写：
     * 快照读会让两个线程都读到同一个旧值，改积分时各自算出的增量相加就会与考勤快照对不上
     * （详见 {@link ActivityAttendanceMapper#selectByIdForUpdate}）。改签到/签退同样是读改写
     * （要拿另一端的时间重算时长、且 {@code updateById} 会整行覆盖），一并受这把锁保护。</p>
     */
    private void applyChange(ActivityAttendanceChange ch, Long auditorId) {
        ActivityAttendance att = attendanceMapper.selectByIdForUpdate(ch.getAttendanceId());
        if (att == null) {
            throw new BusinessException("考勤记录不存在");
        }
        switch (ch.getChangeType()) {
            case CHANGE_CHECKIN -> {
                att.setCheckInTime(parseTime(ch.getNewValue()));
                att.setServiceMinutes(recomputeMinutes(att));
            }
            case CHANGE_CHECKOUT -> {
                att.setCheckOutTime(parseTime(ch.getNewValue()));
                att.setServiceMinutes(recomputeMinutes(att));
            }
            case CHANGE_POINTS -> applyPointsChange(ch, att, auditorId);
            default -> throw new BusinessException("变更项非法");
        }
        attendanceMapper.updateById(att);
    }

    /**
     * 应用积分变更：改考勤快照 + <b>按差额</b>写积分账本（V24）。
     *
     * <p>账本是追加型的，不能像考勤那样「覆盖」；把积分从 8 改到 10 要记的是 +2 而非 10，
     * 否则志愿者余额会凭空多出一份。差额为 0（改成和原值一样）时不入账。</p>
     *
     * <p>流水的 {@code sourceId} 用<b>变更申请 id</b> 而非考勤 id——同一条考勤可被多次修正，
     * 用考勤 id 会撞 {@code uk_source} 唯一约束导致第二次修正入账失败。</p>
     */
    private void applyPointsChange(ActivityAttendanceChange ch, ActivityAttendance att, Long auditorId) {
        int oldAward = att.getPointsAward() == null ? 0 : att.getPointsAward();
        int newAward = parsePoints(ch.getNewValue());
        att.setPointsAward(newAward);

        int delta = newAward - oldAward;
        if (delta != 0) {
            // 带上申请理由：明细页的搜索框搜的是 remark，只写「旧值 → 新值」的话，
            // 志愿者和后台都无法按「为什么改」检索到这笔修正。理由列 512、remark 列同为 512（V24 已按此设计），极端超长仍由 PointService 安全截断，
            // 由 PointService 统一安全截断——完整原文仍在 activity_attendance_change.reason 里，
            // 那才是审计原件，这里只是给人看的展示文案。
            String reason = StringUtils.hasText(ch.getReason()) ? "：" + ch.getReason().trim() : "";
            pointService.record(att.getVolunteerId(), delta, PointSourceType.CORRECTION, ch.getId(),
                    "积分修正（" + oldAward + " → " + newAward + "）" + reason,
                    PointSourceType.OPERATOR_ADMIN, auditorId);
        }
    }

    /** 改签到/签退后重算时长：请假/缺席记 0；缺一边无法重算则保持原值；否则 签退−签到（负数兜底 0）。 */
    private Integer recomputeMinutes(ActivityAttendance att) {
        Integer st = att.getAttendStatus();
        if (Integer.valueOf(ATTEND_LEAVE).equals(st) || Integer.valueOf(ATTEND_ABSENT).equals(st)) {
            return 0;
        }
        if (att.getCheckInTime() == null || att.getCheckOutTime() == null) {
            return att.getServiceMinutes();
        }
        long m = Duration.between(att.getCheckInTime(), att.getCheckOutTime()).toMinutes();
        return m < 0 ? 0 : (int) m;
    }

    private String snapshotOldValue(ActivityAttendance att, Integer changeType) {
        if (changeType == null) {
            throw new BusinessException("变更项不能为空");
        }
        return switch (changeType) {
            case CHANGE_CHECKIN -> att.getCheckInTime() == null ? null : att.getCheckInTime().toString();
            case CHANGE_CHECKOUT -> att.getCheckOutTime() == null ? null : att.getCheckOutTime().toString();
            case CHANGE_POINTS -> att.getPointsAward() == null ? null : String.valueOf(att.getPointsAward());
            default -> throw new BusinessException("变更项非法（1签到/2签退/3积分）");
        };
    }

    private void validateNewValue(Integer changeType, String newValue) {
        if (changeType == CHANGE_POINTS) {
            parsePoints(newValue);
        } else {
            parseTime(newValue);
        }
    }

    private LocalDateTime parseTime(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new BusinessException("时间格式非法（应如 2026-05-29T10:00:00）");
        }
    }

    private int parsePoints(String value) {
        try {
            int p = Integer.parseInt(value.trim());
            if (p < 0) {
                throw new BusinessException("积分不能为负");
            }
            return p;
        } catch (NumberFormatException e) {
            throw new BusinessException("积分格式非法（应为整数）");
        }
    }

    private AttendanceChangeVO toVo(ActivityAttendanceChange ch, ActivityAttendance att,
                                   Map<Long, String> titleById, Map<Long, String> nameById,
                                   Map<Long, ActivitySlot> slotById) {
        AttendanceChangeVO vo = new AttendanceChangeVO();
        vo.setId(ch.getId());
        vo.setAttendanceId(ch.getAttendanceId());
        vo.setChangeType(ch.getChangeType());
        vo.setOldValue(ch.getOldValue());
        vo.setNewValue(ch.getNewValue());
        vo.setReason(ch.getReason());
        vo.setStatus(ch.getStatus());
        vo.setRequestedBy(ch.getRequestedBy());
        vo.setRequestedTime(ch.getRequestedTime());
        vo.setAuditedBy(ch.getAuditedBy());
        vo.setAuditedTime(ch.getAuditedTime());
        vo.setAuditReason(ch.getAuditReason());
        if (att != null) {
            vo.setActivityId(att.getActivityId());
            vo.setActivityTitle(titleById.get(att.getActivityId()));
            vo.setVolunteerId(att.getVolunteerId());
            vo.setVolunteerName(nameById.get(att.getVolunteerId()));
            vo.setSlotId(att.getSlotId());
            ActivitySlot slot = slotById.get(att.getSlotId());
            if (slot != null) {
                vo.setSlotProjectName(slot.getProjectName());
                vo.setSlotStartTime(slot.getStartTime());
                vo.setSlotEndTime(slot.getEndTime());
            }
        }
        return vo;
    }
}
