package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengde.activity.constant.EnrollmentStatus;
import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.entity.ActivityEnrollment;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.vo.EnrollmentAdminVO;
import com.hengde.activity.vo.EnrollmentExportRow;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerDisplayView;
import com.hengde.common.constant.Gender;
import com.hengde.common.constant.Grade;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理端报名管理：报名列表、手动新增、审核通过/拒绝、删除记录、Excel 导出。
 *
 * <p>手动新增委托给 {@link EnrollmentService#manualEnroll}（复用其志愿者维度锁与冲突校验）；
 * 列表/导出按报名时间升序（V1 暂不做「管理团队/临时负责人优先」——其身份映射待 organization 模块）。</p>
 *
 * @author hengde
 */
@Service
public class EnrollmentAdminService {

    private static final int ENROLL_PENDING = EnrollmentStatus.PENDING;
    private static final int ENROLL_APPROVED = EnrollmentStatus.APPROVED;
    private static final int ENROLL_REJECTED = EnrollmentStatus.REJECTED;
    private static final int ENROLL_CANCELLED = EnrollmentStatus.CANCELLED;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ActivityEnrollmentMapper enrollmentMapper;
    private ActivitySlotMapper activitySlotMapper;
    private VolunteerQueryService volunteerQueryService;
    private EnrollmentService enrollmentService;

    @Autowired
    public void setEnrollmentMapper(ActivityEnrollmentMapper enrollmentMapper) {
        this.enrollmentMapper = enrollmentMapper;
    }

    @Autowired
    public void setActivitySlotMapper(ActivitySlotMapper activitySlotMapper) {
        this.activitySlotMapper = activitySlotMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setEnrollmentService(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    /**
     * 报名列表（按活动，可选状态筛选），按报名时间升序。
     */
    public PageResult<EnrollmentAdminVO> list(Long activityId, PageQuery query, Integer status) {
        if (status != null && (status < ENROLL_PENDING || status > ENROLL_CANCELLED)) {
            throw new BusinessException("报名状态取值非法（应为 0~3）");
        }
        Page<ActivityEnrollment> page = query.toPage();
        var wrapper = Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, activityId);
        if (status != null) {
            wrapper.eq(ActivityEnrollment::getStatus, status);
        }
        wrapper.orderByAsc(ActivityEnrollment::getEnrollTime);
        enrollmentMapper.selectPage(page, wrapper);

        List<ActivityEnrollment> records = page.getRecords();
        Map<Long, VolunteerDisplayView> volunteerById = loadVolunteers(records);
        Map<Long, ActivitySlot> slotById = loadSlots(records);

        List<EnrollmentAdminVO> vos = records.stream().map(e -> toAdminVO(e, volunteerById, slotById)).toList();
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 手动新增报名（委托 EnrollmentService 的越权补录）。
     */
    public int manualEnroll(Long activityId, Long volunteerId, List<Long> slotIds, Long adminId) {
        return enrollmentService.manualEnroll(activityId, volunteerId, slotIds, adminId);
    }

    /**
     * 审核通过：仅待审核(0)可通过，置已通过(1)并记审核人/时间。
     *
     * <p>用「条件更新」(where status=0) 保证原子性——两个管理员并发审核同一条时，CAS 式更新只会有一条命中，
     * 另一条 affected rows=0 即被拒，避免「先读后写」覆盖。MyBatis-Plus 对 wrapper 更新会自动追加 is_deleted=0。</p>
     */
    public void approve(Long enrollmentId, Long adminId) {
        // 纯 wrapper 更新不触发 MetaObjectHandler 的 update_time 自动填充，故显式 set 以保持公共字段语义一致
        LocalDateTime now = LocalDateTime.now();
        int rows = enrollmentMapper.update(null, Wrappers.<ActivityEnrollment>lambdaUpdate()
                .set(ActivityEnrollment::getStatus, ENROLL_APPROVED)
                .set(ActivityEnrollment::getAuditBy, adminId)
                .set(ActivityEnrollment::getAuditTime, now)
                .set(ActivityEnrollment::getUpdateTime, now)
                .eq(ActivityEnrollment::getId, enrollmentId)
                .eq(ActivityEnrollment::getStatus, ENROLL_PENDING));
        if (rows != 1) {
            throwAuditConflict(enrollmentId);
        }
    }

    /**
     * 审核拒绝：仅待审核(0)可拒绝，置已拒绝(2)、记原因与审核人/时间。原子性同 {@link #approve}。
     */
    public void reject(Long enrollmentId, String reason, Long adminId) {
        LocalDateTime now = LocalDateTime.now();
        int rows = enrollmentMapper.update(null, Wrappers.<ActivityEnrollment>lambdaUpdate()
                .set(ActivityEnrollment::getStatus, ENROLL_REJECTED)
                .set(ActivityEnrollment::getRejectReason, reason)
                .set(ActivityEnrollment::getAuditBy, adminId)
                .set(ActivityEnrollment::getAuditTime, now)
                .set(ActivityEnrollment::getUpdateTime, now)
                .eq(ActivityEnrollment::getId, enrollmentId)
                .eq(ActivityEnrollment::getStatus, ENROLL_PENDING));
        if (rows != 1) {
            throwAuditConflict(enrollmentId);
        }
    }

    /**
     * 删除报名记录（逻辑删除；与志愿者「取消报名」无关，仅后台清理记录）。
     */
    public void delete(Long enrollmentId) {
        ActivityEnrollment e = enrollmentMapper.selectById(enrollmentId);
        if (e == null) {
            throw new BusinessException("报名记录不存在");
        }
        enrollmentMapper.deleteById(enrollmentId);
    }

    /**
     * 导出报名名单（全量，按报名时间升序）。
     */
    public List<EnrollmentExportRow> exportRows(Long activityId) {
        List<ActivityEnrollment> records = enrollmentMapper.selectList(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, activityId)
                .orderByAsc(ActivityEnrollment::getEnrollTime));
        Map<Long, VolunteerDisplayView> volunteerById = loadVolunteers(records);
        Map<Long, ActivitySlot> slotById = loadSlots(records);
        return records.stream().map(e -> toExportRow(e, volunteerById, slotById)).toList();
    }

    // ---------- 内部辅助 ----------

    /** 条件更新未命中（rows!=1）时，再查一次仅为给出更友好的报错文案——状态判定的真正依据是上面的原子更新。 */
    private void throwAuditConflict(Long enrollmentId) {
        if (enrollmentMapper.selectById(enrollmentId) == null) {
            throw new BusinessException("报名记录不存在");
        }
        throw new BusinessException("该报名不在待审核状态，无法操作");
    }

    /**
     * 同时拉齐报名志愿者与代报名人的展示信息（姓名/学校/手机号）——一次查询，给 toAdminVO 同时使用。
     */
    private Map<Long, VolunteerDisplayView> loadVolunteers(List<ActivityEnrollment> records) {
        Set<Long> ids = new HashSet<>();
        for (ActivityEnrollment e : records) {
            ids.add(e.getVolunteerId());
            if (e.getProxyByVolunteerId() != null) {
                ids.add(e.getProxyByVolunteerId());
            }
        }
        return volunteerQueryService.listDisplayByIds(new ArrayList<>(ids));
    }

    private Map<Long, ActivitySlot> loadSlots(List<ActivityEnrollment> records) {
        List<Long> ids = records.stream().map(ActivityEnrollment::getSlotId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return activitySlotMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(ActivitySlot::getId, Function.identity()));
    }

    private EnrollmentAdminVO toAdminVO(ActivityEnrollment e,
                                        Map<Long, VolunteerDisplayView> volunteerById,
                                        Map<Long, ActivitySlot> slotById) {
        EnrollmentAdminVO vo = new EnrollmentAdminVO();
        vo.setEnrollmentId(e.getId());
        vo.setVolunteerId(e.getVolunteerId());
        vo.setSlotId(e.getSlotId());
        vo.setStatus(e.getStatus());
        vo.setEnrollTime(e.getEnrollTime());
        vo.setRejectReason(e.getRejectReason());
        vo.setAuditTime(e.getAuditTime());
        VolunteerDisplayView v = volunteerById.get(e.getVolunteerId());
        if (v != null) {
            vo.setRealName(v.realName());
            vo.setSchool(v.school());
            vo.setGrade(v.grade());
            vo.setGender(v.gender());
            vo.setPhone(v.phone());
        }
        ActivitySlot slot = slotById.get(e.getSlotId());
        if (slot != null) {
            vo.setProjectName(slot.getProjectName());
            vo.setSlotStartTime(slot.getStartTime());
            vo.setSlotEndTime(slot.getEndTime());
        }
        if (e.getProxyByVolunteerId() != null) {
            vo.setProxyByVolunteerId(e.getProxyByVolunteerId());
            VolunteerDisplayView proxy = volunteerById.get(e.getProxyByVolunteerId());
            if (proxy != null) {
                vo.setProxyByName(proxy.realName());
            }
        }
        return vo;
    }

    private EnrollmentExportRow toExportRow(ActivityEnrollment e,
                                            Map<Long, VolunteerDisplayView> volunteerById,
                                            Map<Long, ActivitySlot> slotById) {
        EnrollmentExportRow row = new EnrollmentExportRow();
        VolunteerDisplayView v = volunteerById.get(e.getVolunteerId());
        if (v != null) {
            row.setRealName(v.realName());
            row.setPhone(v.phone());
            row.setSchool(v.school());
            row.setGrade(gradeLabel(v.grade()));
            row.setGender(genderLabel(v.gender()));
        }
        ActivitySlot slot = slotById.get(e.getSlotId());
        if (slot != null) {
            row.setProjectName(slot.getProjectName());
            row.setSlotStartTime(fmt(slot.getStartTime()));
            row.setSlotEndTime(fmt(slot.getEndTime()));
        }
        row.setStatus(statusLabel(e.getStatus()));
        row.setEnrollTime(fmt(e.getEnrollTime()));
        return row;
    }

    private String fmt(LocalDateTime t) {
        return t == null ? "" : t.format(TIME_FMT);
    }

    private String gradeLabel(Integer code) {
        Grade g = Grade.fromCode(code);
        return g == null ? "" : g.getLabel();
    }

    private String genderLabel(Integer code) {
        Gender g = Gender.fromCode(code);
        return g == null ? "" : g.getLabel();
    }

    private String statusLabel(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case ENROLL_PENDING -> "待审核";
            case ENROLL_APPROVED -> "已通过";
            case ENROLL_REJECTED -> "已拒绝";
            case ENROLL_CANCELLED -> "已取消";
            default -> "";
        };
    }
}
