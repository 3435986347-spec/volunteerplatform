package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengde.activity.constant.ActivityStatus;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivityMessageMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityMessage;
import com.hengde.activity.vo.ActivityMessageVO;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 活动留言（V15，第 3 批·PR1）：志愿者发表/查看活动留言，管理端下架。
 *
 * <p>发表/查看都收敛到「仅已发布活动」（与 {@code ActivityService.detailForVolunteer} 同口径，
 * 草稿/已取消/历史活动等志愿者端不可见的一律报「活动不存在」）。列表对所有已登录用户可见（仅
 * {@code status=1} 正常项，逻辑删除由 MyBatis-Plus 自动排除）；发表人姓名经
 * {@link VolunteerQueryService#listNamesByIds} 批量取（只 select 姓名列、不解密手机号）。
 * 管理端 {@code activity:manage} 删除走逻辑删除（下架）。</p>
 *
 * @author hengde
 */
@Service
public class ActivityMessageService {

    /** 留言正常态 */
    private static final int STATUS_NORMAL = 1;
    /** 活动已发布（仅此态可发/看留言，与志愿者端详情口径一致） */
    private static final int ACTIVITY_PUBLISHED = 1;
    /** 留言内容长度上限（与 DB VARCHAR(500) 一致） */
    private static final int MAX_CONTENT_LEN = 500;

    private ActivityMessageMapper messageMapper;
    private ActivityMapper activityMapper;
    private VolunteerQueryService volunteerQueryService;

    @Autowired
    public void setMessageMapper(ActivityMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    @Autowired
    public void setActivityMapper(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    /** 发表留言：校验活动已发布 + 内容非空且不超长，落 status=1 正常。 */
    public Long post(Long activityId, Long volunteerId, String content) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException("留言内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LEN) {
            throw new BusinessException("留言内容不超过 500 字");
        }
        requirePublishedActivity(activityId);
        ActivityMessage msg = new ActivityMessage();
        msg.setActivityId(activityId);
        msg.setVolunteerId(volunteerId);
        msg.setContent(content);
        msg.setStatus(STATUS_NORMAL);
        messageMapper.insert(msg);
        return msg.getId();
    }

    /** 活动留言列表：仅已发布活动、仅正常项，按 id 倒序，带发表人姓名（志愿者端）。 */
    public PageResult<ActivityMessageVO> list(Long activityId, PageQuery query) {
        requirePublishedActivity(activityId);
        return pageMessages(activityId, query);
    }

    /**
     * 管理端留言列表：不限发布状态（含已结束/历史/草稿，供后台审阅、下架），但<b>排除审核域 4/5</b>，
     * 与常规 {@code activity:menu} 的可见边界一致——审核域活动只能经 {@code activity:publish-audit} 路径触达，
     * 不允许「有 menu 权 + 知道 id」从这里读到待审/驳回活动的留言。
     */
    public PageResult<ActivityMessageVO> listForAdmin(Long activityId, PageQuery query) {
        Activity a = activityMapper.selectById(activityId);
        if (a == null || ActivityStatus.isUnderReview(a.getStatus())) {
            throw new BusinessException("活动不存在");
        }
        return pageMessages(activityId, query);
    }

    /** 留言分页 + 发表人姓名映射（list / listForAdmin 共用）。 */
    private PageResult<ActivityMessageVO> pageMessages(Long activityId, PageQuery query) {
        Page<ActivityMessage> page = query.toPage();
        messageMapper.selectPage(page, Wrappers.<ActivityMessage>lambdaQuery()
                .eq(ActivityMessage::getActivityId, activityId)
                .eq(ActivityMessage::getStatus, STATUS_NORMAL)
                .orderByDesc(ActivityMessage::getId));

        List<Long> volunteerIds = page.getRecords().stream()
                .map(ActivityMessage::getVolunteerId).distinct().toList();
        Map<Long, String> nameById = volunteerQueryService.listNamesByIds(volunteerIds);

        List<ActivityMessageVO> vos = page.getRecords().stream().map(m -> {
            ActivityMessageVO vo = new ActivityMessageVO();
            vo.setId(m.getId());
            vo.setActivityId(m.getActivityId());
            vo.setVolunteerId(m.getVolunteerId());
            vo.setContent(m.getContent());
            vo.setCreateTime(m.getCreateTime());
            vo.setVolunteerName(nameById.get(m.getVolunteerId()));
            return vo;
        }).toList();
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 管理端下架留言（逻辑删除）。 */
    public void delete(Long messageId) {
        if (messageMapper.deleteById(messageId) != 1) {
            throw new BusinessException("留言不存在或已删除");
        }
    }

    /** 活动须存在且已发布（草稿/已取消/历史活动等志愿者端不可见的统一报「活动不存在」）。 */
    private void requirePublishedActivity(Long activityId) {
        Activity a = activityMapper.selectById(activityId);
        if (a == null || !Integer.valueOf(ACTIVITY_PUBLISHED).equals(a.getStatus())) {
            throw new BusinessException("活动不存在");
        }
    }
}
