package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.dao.ActivityViolationMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.entity.ActivityViolation;
import com.hengde.activity.vo.ViolationRecordVO;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 活动违规审核（V2 第 5 批）。
 *
 * <p><b>需求出处</b>：xlsx Row 59 后台首页待办里单列了「<b>活动违规审核</b>」这一项；
 * Row 41 F「各类违规记录和奖励均需<b>组织部同学审核才可显示</b>」。</p>
 *
 * <p><b>为什么违规记录要单独过一道审，而不是等奖惩单那一道</b>：现场记录是负责人的
 * <b>工作底稿</b>——他在活动现场凭观察点几下就落库了。未经核实直接呈现给被记的那个人，
 * 等于把一面之词当成定论；而 Row 59 把「活动违规审核」列成一个<b>独立的待办队列</b>，
 * 说明需求侧本来就把它看作一道独立的关。通过之后它才对志愿者可见，
 * 也才够格作为开一张处罚单的依据。</p>
 *
 * @author hengde
 */
@Service
public class ViolationReviewService {

    private ActivityViolationMapper violationMapper;
    private ActivityMapper activityMapper;
    private ActivitySlotMapper slotMapper;
    private VolunteerQueryService volunteerQueryService;

    @Autowired
    public void setViolationMapper(ActivityViolationMapper violationMapper) {
        this.violationMapper = violationMapper;
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
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    /**
     * 审核队列（可按状态/活动筛）。
     *
     * @param reviewStatus 缺省 0（待审核）——待办队列的默认视图就是「还没处理的」
     */
    public PageResult<ViolationRecordVO> list(PageQuery query, Integer reviewStatus, Long activityId) {
        Page<ActivityViolation> page = query.toPage();
        violationMapper.selectPage(page, Wrappers.<ActivityViolation>lambdaQuery()
                .eq(ActivityViolation::getReviewStatus,
                        reviewStatus == null ? ActivityViolation.REVIEW_PENDING : reviewStatus)
                .eq(activityId != null, ActivityViolation::getActivityId, activityId)
                .orderByDesc(ActivityViolation::getId));
        return PageResult.of(toVos(page.getRecords()), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 一条<b>已通过审核</b>的现场违规，供 honor 据以开处罚单。
     *
     * @param volunteerId 违规者
     * @param activityId  所属活动
     * @param slotId      所属场次
     */
    public record ApprovedViolation(Long violationId, Long volunteerId, Long activityId, Long slotId) {
    }

    /**
     * 取一条<b>已通过组织部审核</b>的违规；不存在或未通过返回 {@code null}。
     *
     * <p>这是 honor 开处罚单时的<b>跨域只读窄接口</b>——honor 不直连
     * {@code ActivityViolationMapper}（项目约定：领域间通过对方 service 接口调用）。
     * 出参只给归属，不外泄实体。</p>
     *
     * <p><b>「已通过」是硬条件</b>：未经核实的现场记录只是负责人的一面之词，
     * 拿它直接开出一张会扣分、会限制人的处罚单，等于把 Row 41 F 那道审核绕过去了。</p>
     */
    public ApprovedViolation findApproved(Long violationId) {
        if (violationId == null) {
            return null;
        }
        ActivityViolation v = violationMapper.selectOne(Wrappers.<ActivityViolation>lambdaQuery()
                .eq(ActivityViolation::getId, violationId)
                .eq(ActivityViolation::getReviewStatus, ActivityViolation.REVIEW_APPROVED));
        return v == null ? null
                : new ApprovedViolation(v.getId(), v.getVolunteerId(), v.getActivityId(), v.getSlotId());
    }

    // Row 59 首页待办里「活动违规审核」的那个数字，用上面 list() 返回的 PageResult.total 即可
    // （`reviewStatus=0&size=1`）。刻意【不再单开一个 count 接口或看板字段】：
    // DashboardVO 的既有决定就是「待办计数由各自权限受控列表接口的 total 提供」，
    // 另开一个出处会让同一个数字有两套口径，而那正是本项目反复在修的问题。

    /** 审核通过：此后该条对志愿者可见，也才够格作为开处罚单的依据。 */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, Long adminId) {
        decide(id, ActivityViolation.REVIEW_APPROVED, null, adminId);
    }

    /** 驳回：记原因，志愿者仍然看不到这条。 */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String reason, Long adminId) {
        if (reason == null || reason.isBlank()) {
            // 驳回等于否定负责人的现场判断，不写理由他无从改正，也无从申辩
            throw new BusinessException("驳回必须填写原因");
        }
        decide(id, ActivityViolation.REVIEW_REJECTED, reason, adminId);
    }

    /**
     * 审核落库，<b>CAS：只有仍处于「待审核」的行可以被裁决</b>。
     *
     * <p>把「只能审待审核的」写进 UPDATE 的 WHERE 而不是先查后判：两名组织部同学同时打开
     * 待办队列点了同一条，先查后判会让后一次覆盖前一次的结论与审核人，
     * 而留痕上完全看不出发生过覆盖。这与勋章那批修过的是同一形状。</p>
     */
    private void decide(Long id, int target, String reason, Long adminId) {
        int rows = violationMapper.update(null, Wrappers.<ActivityViolation>lambdaUpdate()
                .set(ActivityViolation::getReviewStatus, target)
                .set(ActivityViolation::getReviewedBy, adminId)
                .set(ActivityViolation::getReviewTime, java.time.LocalDateTime.now())
                .set(ActivityViolation::getRejectReason, reason)
                .set(ActivityViolation::getUpdateTime, java.time.LocalDateTime.now())
                .eq(ActivityViolation::getId, id)
                .eq(ActivityViolation::getReviewStatus, ActivityViolation.REVIEW_PENDING));
        if (rows != 1) {
            throw new BusinessException("该违规记录不存在或已被审核");
        }
    }

    private List<ViolationRecordVO> toVos(List<ActivityViolation> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> nameById = volunteerQueryService.listNamesByIds(
                rows.stream().map(ActivityViolation::getVolunteerId).filter(Objects::nonNull).distinct().toList());
        Map<Long, String> titleById = new HashMap<>();
        List<Long> activityIds = rows.stream().map(ActivityViolation::getActivityId)
                .filter(Objects::nonNull).distinct().toList();
        if (!activityIds.isEmpty()) {
            for (Activity a : activityMapper.selectBatchIds(activityIds)) {
                titleById.put(a.getId(), a.getTitle());
            }
        }
        Map<Long, ActivitySlot> slotById = new HashMap<>();
        List<Long> slotIds = rows.stream().map(ActivityViolation::getSlotId)
                .filter(Objects::nonNull).distinct().toList();
        if (!slotIds.isEmpty()) {
            for (ActivitySlot s : slotMapper.selectBatchIds(slotIds)) {
                slotById.put(s.getId(), s);
            }
        }
        return rows.stream().map(v -> {
            ViolationRecordVO vo = new ViolationRecordVO();
            vo.setId(v.getId());
            vo.setActivityId(v.getActivityId());
            vo.setActivityTitle(titleById.get(v.getActivityId()));
            vo.setSlotId(v.getSlotId());
            ActivitySlot s = slotById.get(v.getSlotId());
            if (s != null) {
                vo.setSlotProjectName(s.getProjectName());
                vo.setSlotStartTime(s.getStartTime());
                vo.setSlotEndTime(s.getEndTime());
            }
            vo.setVolunteerId(v.getVolunteerId());
            vo.setVolunteerName(nameById.get(v.getVolunteerId()));
            vo.setViolationType(v.getViolationType());
            vo.setDescription(v.getDescription());
            vo.setRecordedBy(v.getRecordedBy());
            vo.setRecordedTime(v.getRecordedTime());
            vo.setReviewStatus(v.getReviewStatus());
            vo.setReviewTime(v.getReviewTime());
            vo.setRejectReason(v.getRejectReason());
            return vo;
        }).toList();
    }
}
