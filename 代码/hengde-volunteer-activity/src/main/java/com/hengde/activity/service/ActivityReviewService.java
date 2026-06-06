package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.vo.ActivityReviewVO;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 活动发布审核（V19）：小程序（志愿者端）管理团队提交的活动落「待审核发布」，
 * 部长在后台审核——通过上线、驳回记原因。后台 {@code /a} 直接发布的活动不进此队列。
 *
 * <p>用 {@code activity.status} 流转：{@value #PENDING}待审核 → 通过={@value #PUBLISHED}已发布 /
 * 驳回={@value #REJECTED}已驳回。审核动作用 CAS（仅 status=待审核 时生效）防并发重复审核。
 * 提交人记在 {@code activity.create_by}（volunteer.id），列表回填姓名供识别。</p>
 *
 * @author hengde
 */
@Service
public class ActivityReviewService {

    /** 待审核发布 */
    private static final int PENDING = 4;
    /** 已发布（审核通过上线） */
    private static final int PUBLISHED = 1;
    /** 发布被驳回 */
    private static final int REJECTED = 5;
    /** 驳回原因长度上限（与 DB 列 VARCHAR(512) 对齐；service 兜底防内部调用绕过 DTO 校验） */
    private static final int MAX_REASON_LEN = 512;

    private ActivityMapper activityMapper;
    private VolunteerQueryService volunteerQueryService;

    @Autowired
    public void setActivityMapper(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    /**
     * 活动发布审核列表，按提交时间倒序、带提交人姓名。
     *
     * <p>{@code status} 为空默认查待审核(4)；可传 5 查已驳回（否则驳回记录无列表入口）。
     * 仅允许审核域状态 4/5——传其他值拒绝，避免借此越界查常规活动。</p>
     */
    public PageResult<ActivityReviewVO> reviews(PageQuery query, Integer status) {
        int effective = status == null ? PENDING : status;
        if (effective != PENDING && effective != REJECTED) {
            throw new BusinessException("审核列表仅支持 status=4 待审核 / 5 已驳回");
        }
        IPage<Activity> page = activityMapper.selectPage(query.toPage(), Wrappers.<Activity>lambdaQuery()
                .eq(Activity::getStatus, effective)
                .orderByDesc(Activity::getCreateTime));
        List<Activity> rows = page.getRecords();
        List<Long> submitterIds = rows.stream().map(Activity::getCreateBy).filter(java.util.Objects::nonNull).toList();
        Map<Long, String> nameById = submitterIds.isEmpty()
                ? Map.of()
                : volunteerQueryService.listNamesByIds(submitterIds);
        List<ActivityReviewVO> vos = rows.stream().map(a -> toVO(a, nameById)).collect(Collectors.toList());
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 审核通过：待审核 → 已发布（上线）。CAS 仅在 status=待审核 时生效，防并发重复审核。
     */
    public void approve(Long activityId, Long adminId) {
        requireAuditor(adminId);
        // wrapper update(null,…) 不触发 MetaObjectHandler.updateFill，须显式刷 update_time（与库内其他 CAS 路径一致）
        LocalDateTime now = LocalDateTime.now();
        int rows = activityMapper.update(null, Wrappers.<Activity>lambdaUpdate()
                .set(Activity::getStatus, PUBLISHED)
                .set(Activity::getPublishReviewBy, adminId)
                .set(Activity::getPublishReviewTime, now)
                .set(Activity::getUpdateTime, now)
                .eq(Activity::getId, activityId)
                .eq(Activity::getStatus, PENDING));
        if (rows != 1) {
            throwConflict(activityId);
        }
    }

    /**
     * 审核驳回：待审核 → 已驳回，记原因。CAS 仅在 status=待审核 时生效。
     */
    public void reject(Long activityId, String reason, Long adminId) {
        requireAuditor(adminId);
        if (reason != null && reason.length() > MAX_REASON_LEN) {
            throw new BusinessException("驳回原因不超过 " + MAX_REASON_LEN + " 字");
        }
        // wrapper update(null,…) 不触发 MetaObjectHandler.updateFill，须显式刷 update_time（与库内其他 CAS 路径一致）
        LocalDateTime now = LocalDateTime.now();
        int rows = activityMapper.update(null, Wrappers.<Activity>lambdaUpdate()
                .set(Activity::getStatus, REJECTED)
                .set(Activity::getPublishRejectReason, reason)
                .set(Activity::getPublishReviewBy, adminId)
                .set(Activity::getPublishReviewTime, now)
                .set(Activity::getUpdateTime, now)
                .eq(Activity::getId, activityId)
                .eq(Activity::getStatus, PENDING));
        if (rows != 1) {
            throwConflict(activityId);
        }
    }

    private void requireAuditor(Long adminId) {
        if (adminId == null) {
            throw new BusinessException("审核人不能为空");
        }
    }

    /** CAS 未命中：区分「不存在」与「不在待审核状态」给出准确文案。 */
    private void throwConflict(Long activityId) {
        if (activityMapper.selectById(activityId) == null) {
            throw new BusinessException("活动不存在");
        }
        throw new BusinessException("活动不在待审核状态");
    }

    private ActivityReviewVO toVO(Activity a, Map<Long, String> nameById) {
        ActivityReviewVO vo = new ActivityReviewVO();
        vo.setId(a.getId());
        vo.setSerialNo(a.getSerialNo());
        vo.setTitle(a.getTitle());
        vo.setLocation(a.getLocation());
        vo.setStartTime(a.getStartTime());
        vo.setEndTime(a.getEndTime());
        vo.setSubmitterId(a.getCreateBy());
        vo.setSubmitterName(a.getCreateBy() == null ? null : nameById.get(a.getCreateBy()));
        vo.setSubmitTime(a.getCreateTime());
        return vo;
    }
}
