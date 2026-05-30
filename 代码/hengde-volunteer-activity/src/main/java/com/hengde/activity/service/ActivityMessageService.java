package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivityMessageMapper;
import com.hengde.activity.entity.ActivityMessage;
import com.hengde.activity.vo.ActivityMessageVO;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerDisplayView;
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
 * <p>列表对所有已登录用户可见（仅 {@code status=1} 正常项，逻辑删除由 MyBatis-Plus 自动排除）；
 * 发表人姓名经 {@link VolunteerQueryService#listDisplayByIds} 批量取，仅取姓名、不外泄手机号。
 * 管理端 {@code activity:manage} 删除走逻辑删除（下架）。</p>
 *
 * @author hengde
 */
@Service
public class ActivityMessageService {

    private static final int STATUS_NORMAL = 1;

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

    /** 发表留言：校验活动存在 + 内容非空，落 status=1 正常。 */
    public Long post(Long activityId, Long volunteerId, String content) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException("留言内容不能为空");
        }
        if (activityMapper.selectById(activityId) == null) {
            throw new BusinessException("活动不存在");
        }
        ActivityMessage msg = new ActivityMessage();
        msg.setActivityId(activityId);
        msg.setVolunteerId(volunteerId);
        msg.setContent(content);
        msg.setStatus(STATUS_NORMAL);
        messageMapper.insert(msg);
        return msg.getId();
    }

    /** 活动留言列表：仅正常项，按 id 倒序，带发表人姓名。 */
    public PageResult<ActivityMessageVO> list(Long activityId, PageQuery query) {
        Page<ActivityMessage> page = query.toPage();
        messageMapper.selectPage(page, Wrappers.<ActivityMessage>lambdaQuery()
                .eq(ActivityMessage::getActivityId, activityId)
                .eq(ActivityMessage::getStatus, STATUS_NORMAL)
                .orderByDesc(ActivityMessage::getId));

        List<Long> volunteerIds = page.getRecords().stream()
                .map(ActivityMessage::getVolunteerId).distinct().toList();
        Map<Long, VolunteerDisplayView> displayById = volunteerQueryService.listDisplayByIds(volunteerIds);

        List<ActivityMessageVO> vos = page.getRecords().stream().map(m -> {
            ActivityMessageVO vo = new ActivityMessageVO();
            vo.setId(m.getId());
            vo.setActivityId(m.getActivityId());
            vo.setVolunteerId(m.getVolunteerId());
            vo.setContent(m.getContent());
            vo.setCreateTime(m.getCreateTime());
            VolunteerDisplayView d = displayById.get(m.getVolunteerId());
            if (d != null) {
                vo.setVolunteerName(d.realName());
            }
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
}
