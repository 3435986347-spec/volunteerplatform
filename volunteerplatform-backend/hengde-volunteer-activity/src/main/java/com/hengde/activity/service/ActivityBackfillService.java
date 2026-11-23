package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityBackfillMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.constant.ActivityStatus;
import com.hengde.activity.constant.AttendStatus;
import com.hengde.activity.constant.AuditStatus;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.constant.PointsFactor;
import com.hengde.activity.constant.PointsStatus;
import com.hengde.activity.constant.SecretaryStatus;
import com.hengde.activity.dto.BackfillRequestDTO;
import com.hengde.activity.event.AttendanceConfirmedEvent;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.ActivityBackfill;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.vo.ActivityBackfillVO;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerBackfillView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 活动补录与部长审核（V16，第 3 批·PR3）。
 *
 * <p>组织部按身份证/手机号精确匹配志愿者 + 指定时间段申请补录 → 落待审记录，<b>不立即生效</b>；
 * 部长二次审核通过后才生效。通过即<b>终态</b>（跳过秘书部确认）：同事务直接落一条已确认
 * （{@code secretary_status=1}）的考勤行，服务时长 = 该时间段时长；普通活动按
 * {@link ServiceRecordService#computePoints} 同一倍率口径算并发积分，历史活动（{@code is_historical=1}）
 * 只记时长、不发积分（积分发放与否在申请时已据活动快照到 {@code grant_points}）。</p>
 *
 * <p>审核用 CAS 条件更新（status 0→1 / 0→2）保原子防重复审核；申请/审核入口对 operatorId 硬校验非空；
 * 补录仅针对「该活动尚无考勤记录」的志愿者，申请与生效时各查一次防重复落账。</p>
 *
 * @author hengde
 */
@Service
public class ActivityBackfillService {

    private static final int STATUS_PENDING = AuditStatus.PENDING;
    private static final int STATUS_APPROVED = AuditStatus.APPROVED;
    private static final int STATUS_REJECTED = AuditStatus.REJECTED;

    private static final int GRANT_POINTS = 1;
    private static final int NO_POINTS = 0;

    private static final int ATTEND_NORMAL = AttendStatus.NORMAL;
    private static final int CHECKIN_METHOD_BACKEND = 3;
    private static final int SECRETARY_CONFIRMED = SecretaryStatus.CONFIRMED;
    private static final int POINTS_GRANTED = PointsStatus.GRANTED;
    private static final int FACTOR_NORMAL = PointsFactor.NORMAL;

    private ActivityBackfillMapper backfillMapper;
    private ActivityAttendanceMapper attendanceMapper;
    private ActivitySlotMapper slotMapper;
    private ActivityMapper activityMapper;
    private ServiceRecordService serviceRecordService;
    private VolunteerQueryService volunteerQueryService;
    private PointService pointService;
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Autowired
    public void setBackfillMapper(ActivityBackfillMapper backfillMapper) {
        this.backfillMapper = backfillMapper;
    }

    @Autowired
    public void setAttendanceMapper(ActivityAttendanceMapper attendanceMapper) {
        this.attendanceMapper = attendanceMapper;
    }

    @Autowired
    public void setSlotMapper(ActivitySlotMapper slotMapper) {
        this.slotMapper = slotMapper;
    }

    @Autowired
    public void setActivityMapper(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    @Autowired
    public void setPointService(PointService pointService) {
        this.pointService = pointService;
    }

    @Autowired
    public void setServiceRecordService(ServiceRecordService serviceRecordService) {
        this.serviceRecordService = serviceRecordService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    /**
     * 组织部申请补录（不立即生效）：匹配志愿者 + 校验时间段归属 + 防重复，落一条待审记录。
     *
     * @return 新建补录记录 id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long requestBackfill(Long activityId, BackfillRequestDTO dto, Long requesterId) {
        if (requesterId == null) {
            throw new BusinessException("操作人不能为空");
        }
        Activity activity = activityMapper.selectById(activityId);
        // 审核域（待审核 4/驳回 5）活动不可补录——否则知道 id 的组织部能给未上线/已驳回活动落服务记录与积分
        if (activity == null || ActivityStatus.isUnderReview(activity.getStatus())) {
            throw new BusinessException("活动不存在");
        }
        VolunteerBackfillView v = volunteerQueryService.findForBackfill(dto.getIdCard(), dto.getPhone(), dto.getName());
        if (v == null) {
            throw new BusinessException("未匹配到志愿者（请核对手机号/身份证）");
        }
        ActivitySlot slot = slotMapper.selectById(dto.getSlotId());
        if (slot == null || !activityId.equals(slot.getActivityId())) {
            throw new BusinessException("时间段不存在或不属于该活动");
        }
        if (hasAttendance(activityId, slot.getId(), v.id())) {
            throw new BusinessException("该志愿者已有该活动的考勤记录，无需补录");
        }

        ActivityBackfill bf = new ActivityBackfill();
        bf.setActivityId(activityId);
        bf.setVolunteerId(v.id());
        bf.setSlotId(slot.getId());
        bf.setServiceMinutes(slotMinutes(slot));
        bf.setGrantPoints(Integer.valueOf(1).equals(activity.getIsHistorical()) ? NO_POINTS : GRANT_POINTS);
        bf.setMatchedBy(StringUtils.hasText(dto.getIdCard()) ? "idCard" : "phone");
        bf.setStatus(STATUS_PENDING);
        bf.setReason(dto.getReason());
        bf.setRequestedBy(requesterId);
        bf.setRequestedTime(LocalDateTime.now());
        backfillMapper.insert(bf);
        return bf.getId();
    }

    /** 补录申请列表（可按状态筛选），带出活动名/志愿者姓名。 */
    public PageResult<ActivityBackfillVO> list(PageQuery query, Integer status) {
        Page<ActivityBackfill> page = query.toPage();
        var wrapper = Wrappers.<ActivityBackfill>lambdaQuery();
        if (status != null) {
            wrapper.eq(ActivityBackfill::getStatus, status);
        }
        wrapper.orderByDesc(ActivityBackfill::getId);
        backfillMapper.selectPage(page, wrapper);

        List<ActivityBackfill> recs = page.getRecords();
        List<Long> activityIds = recs.stream().map(ActivityBackfill::getActivityId).distinct().toList();
        Map<Long, Activity> actById = activityIds.isEmpty() ? Map.of()
                : activityMapper.selectBatchIds(activityIds).stream()
                .collect(Collectors.toMap(Activity::getId, Function.identity()));
        List<Long> volunteerIds = recs.stream().map(ActivityBackfill::getVolunteerId).distinct().toList();
        Map<Long, String> nameById = volunteerQueryService.listNamesByIds(volunteerIds);

        List<ActivityBackfillVO> vos = recs.stream()
                .map(b -> toVo(b, actById.get(b.getActivityId()), nameById.get(b.getVolunteerId()))).toList();
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 部长审核通过：CAS 待审→通过，并在同一事务内落已确认考勤行（普通活动发积分/历史只记时长）。 */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long backfillId, String auditReason, Long auditorId) {
        if (auditorId == null) {
            throw new BusinessException("审核人不能为空");
        }
        ActivityBackfill bf = backfillMapper.selectById(backfillId);
        if (bf == null || !Integer.valueOf(STATUS_PENDING).equals(bf.getStatus())) {
            throw new BusinessException("补录申请不存在或已审核");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = backfillMapper.update(null, Wrappers.<ActivityBackfill>lambdaUpdate()
                .set(ActivityBackfill::getStatus, STATUS_APPROVED)
                .set(ActivityBackfill::getAuditedBy, auditorId)
                .set(ActivityBackfill::getAuditedTime, now)
                .set(ActivityBackfill::getAuditReason, auditReason)
                .set(ActivityBackfill::getUpdateTime, now)
                .eq(ActivityBackfill::getId, backfillId)
                .eq(ActivityBackfill::getStatus, STATUS_PENDING));
        if (rows != 1) {
            throw new BusinessException("补录申请已被处理，请刷新重试");
        }
        applyBackfill(bf, auditorId, now);
    }

    /** 部长审核拒绝：CAS 待审→拒绝，不落账。 */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long backfillId, String auditReason, Long auditorId) {
        if (auditorId == null) {
            throw new BusinessException("审核人不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = backfillMapper.update(null, Wrappers.<ActivityBackfill>lambdaUpdate()
                .set(ActivityBackfill::getStatus, STATUS_REJECTED)
                .set(ActivityBackfill::getAuditedBy, auditorId)
                .set(ActivityBackfill::getAuditedTime, now)
                .set(ActivityBackfill::getAuditReason, auditReason)
                .set(ActivityBackfill::getUpdateTime, now)
                .eq(ActivityBackfill::getId, backfillId)
                .eq(ActivityBackfill::getStatus, STATUS_PENDING));
        if (rows != 1) {
            throw new BusinessException("补录申请不存在或已处理");
        }
    }

    // ---------- 内部 ----------

    /** 通过后落账：建一条已确认考勤行，普通活动发积分、历史活动只记时长。 */
    private void applyBackfill(ActivityBackfill bf, Long auditorId, LocalDateTime now) {
        // 再查一次防并发/补录重复落账（活动考勤无 (活动,志愿者) 唯一约束，靠应用层兜底）
        if (hasAttendance(bf.getActivityId(), bf.getSlotId(), bf.getVolunteerId())) {
            throw new BusinessException("该志愿者已有该活动的考勤记录，补录冲突");
        }
        Activity activity = activityMapper.selectById(bf.getActivityId());
        // 落账前再查一次（防 request→approve 之间活动状态变化）：审核域活动一律拒绝落账
        if (activity == null || ActivityStatus.isUnderReview(activity.getStatus())) {
            throw new BusinessException("活动不存在");
        }
        // V30：考勤按场次，slot_id NOT NULL，故场次必须存在才能落账。
        // 从提单到审批之间场次可能被删（活动修改允许改岗位时间段），这里显式拒绝，
        // 而不是先 slot.getId() 解引用再判空——那样删过场次的单子会 NPE 成 500 而非给出可读原因。
        ActivitySlot slot = slotMapper.selectById(bf.getSlotId());
        if (slot == null || !bf.getActivityId().equals(slot.getActivityId())) {
            throw new BusinessException("补录的活动时间段已不存在，无法落账，请驳回后重新提交");
        }

        ActivityAttendance att = new ActivityAttendance();
        att.setSlotId(slot.getId());
        att.setActivityId(bf.getActivityId());
        att.setVolunteerId(bf.getVolunteerId());
        att.setCheckInTime(slot.getStartTime());
        att.setCheckOutTime(slot.getEndTime());
        att.setServiceMinutes(bf.getServiceMinutes());
        att.setAttendStatus(ATTEND_NORMAL);
        att.setCheckInMethod(CHECKIN_METHOD_BACKEND);
        att.setCheckInBy(auditorId);
        att.setCheckOutBy(auditorId);
        att.setSecretaryStatus(SECRETARY_CONFIRMED);
        att.setSecretaryBy(auditorId);
        att.setSecretaryTime(now);
        // 补录通过即终结积分流程：普通活动发实算分、历史活动记 0 分，二者都置 points_status=已发放，
        // 杜绝后续正常 grantPoints（CAS 要求 points_status=未发放）把历史补录二次发成积分。
        int award = Integer.valueOf(GRANT_POINTS).equals(bf.getGrantPoints())
                ? serviceRecordService.computePoints(activity, att, FACTOR_NORMAL) : 0;
        att.setPointsAward(award);
        att.setPointsStatus(POINTS_GRANTED);
        att.setPointsFactor(FACTOR_NORMAL);
        try {
            attendanceMapper.insert(att);
        } catch (DuplicateKeyException e) {
            // uk_activity_volunteer 兜底并发：另一笔补录/签到已先落同一(活动,志愿者)考勤行
            throw new BusinessException("该志愿者已有该活动的考勤记录，补录冲突");
        }
        // 同事务写积分账本（V24）。历史活动 award=0（只记时长不发分）故不入账——账本只记真实变动。
        // 入账须在 insert 之后：sourceId 用刚生成的考勤行 id，靠 uk_source 保证同一考勤不会重复入账。
        if (award != 0) {
            pointService.record(bf.getVolunteerId(), award, PointSourceType.ACTIVITY, att.getId(),
                    "活动补录「" + activity.getTitle() + "」", PointSourceType.OPERATOR_ADMIN, auditorId);
        }
        // 补录落的考勤【直接就是已确认态】，走不到 ServiceRecordService#secretaryConfirm，
        // 因此必须在这里补发同一个事件，否则补录进来的志愿者永远拿不到电子证书
        // （xlsx Row 36「参加完活动后自动生成」并不区分是正常签到还是后台补录）。
        eventPublisher.publishEvent(new AttendanceConfirmedEvent(
                att.getId(), att.getActivityId(), att.getSlotId(), att.getVolunteerId()));
    }

    /**
     * 判重下沉到<b>场次</b>（V30）。
     *
     * <p><b>需求来源</b>：`小程序设想【第十版】.xlsx` · 前端 sheet · Row 65「D 前端备注」的「活动补录」两条，
     * 逐字写着「添加<b>指定时间段</b>即可获得时长」「添加指定指定志愿者参加<b>时间段</b>即可获得时长」——
     * 补录本来就是<b>按时间段（场次）</b>补的，不是按活动补。</p>
     *
     * <p>补录单本就带 {@code slotId}，若仍按「活动 + 志愿者」判重，
     * 同一活动第二个场次的补录会被误判成「已有考勤」而拒绝——那正是考勤场次化要消除的错误。</p>
     */
    private boolean hasAttendance(Long activityId, Long slotId, Long volunteerId) {
        Long c = attendanceMapper.selectCount(Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getActivityId, activityId)
                .eq(ActivityAttendance::getSlotId, slotId)
                .eq(ActivityAttendance::getVolunteerId, volunteerId));
        return c != null && c > 0;
    }

    private int slotMinutes(ActivitySlot slot) {
        if (slot.getStartTime() == null || slot.getEndTime() == null) {
            return 0;
        }
        long m = Duration.between(slot.getStartTime(), slot.getEndTime()).toMinutes();
        return m < 0 ? 0 : (int) m;
    }

    private ActivityBackfillVO toVo(ActivityBackfill b, Activity activity, String volunteerName) {
        ActivityBackfillVO vo = new ActivityBackfillVO();
        vo.setId(b.getId());
        vo.setActivityId(b.getActivityId());
        vo.setVolunteerId(b.getVolunteerId());
        vo.setSlotId(b.getSlotId());
        vo.setServiceMinutes(b.getServiceMinutes());
        vo.setGrantPoints(b.getGrantPoints());
        vo.setMatchedBy(b.getMatchedBy());
        vo.setStatus(b.getStatus());
        vo.setReason(b.getReason());
        vo.setRequestedBy(b.getRequestedBy());
        vo.setRequestedTime(b.getRequestedTime());
        vo.setAuditedBy(b.getAuditedBy());
        vo.setAuditedTime(b.getAuditedTime());
        vo.setAuditReason(b.getAuditReason());
        if (activity != null) {
            vo.setActivityTitle(activity.getTitle());
        }
        vo.setVolunteerName(volunteerName);
        return vo;
    }
}
