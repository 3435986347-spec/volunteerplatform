package com.hengde.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.VolunteerNotificationMapper;
import com.hengde.auth.entity.VolunteerNotification;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 志愿者站内提示：写入口 + 只读查询。
 *
 * <p><b>需求出处</b>：xlsx Row 41 F「…审核之后，<b>志愿者会收到提示</b>，并有 7 天申诉期」。</p>
 *
 * <p><b>写入必须与业务动作同事务</b>：{@link #notify} 不自己开事务，也不吞异常——
 * 它由调用方（如奖惩审核）的事务带着走。这样「审核通过了但提示没发出去」不可能发生，
 * 反过来「提示发了但审核回滚了」也不可能。<b>刻意不做成异步/事件</b>：
 * 那会把一个两行的写操作换成一个「大概率会到、失败没人知道」的通道，
 * 而这条提示是 7 天申诉期的起点，志愿者收不到就等于申诉权没有被告知。</p>
 *
 * @author hengde
 */
@Service
public class NotificationService {

    private VolunteerNotificationMapper notificationMapper;

    @Autowired
    public void setNotificationMapper(VolunteerNotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    /**
     * 发一条站内提示。<b>由调用方的事务带着走，不单独开事务。</b>
     *
     * <p>{@code volunteerId} 为空直接返回：调用方那边已经校验过收件人存在，
     * 在这里再抛一次只会把报错文案指向「提示」而不是真正出问题的那一步。</p>
     *
     * @param type    见 {@link VolunteerNotification#TYPE_REWARD_PUNISH_APPROVED}
     * @param bizType 关联业务类型，可为 null
     * @param bizId   关联业务 id，前端据此跳详情，可为 null
     */
    public void notify(Long volunteerId, int type, String title, String content,
                       Integer bizType, Long bizId) {
        if (volunteerId == null) {
            return;
        }
        VolunteerNotification n = new VolunteerNotification();
        n.setVolunteerId(volunteerId);
        n.setType(type);
        n.setTitle(title);
        n.setContent(content);
        n.setBizType(bizType);
        n.setBizId(bizId);
        n.setIsRead(VolunteerNotification.UNREAD);
        notificationMapper.insert(n);
    }

    /** 我的提示，按 id 倒序分页（新的在前）。 */
    public PageResult<VolunteerNotification> myNotifications(Long volunteerId, PageQuery query) {
        if (volunteerId == null) {
            return PageResult.of(java.util.List.of(), 0, query.getPage(), query.getSize());
        }
        return PageResult.of(notificationMapper.selectPage(query.toPage(),
                Wrappers.<VolunteerNotification>lambdaQuery()
                        .eq(VolunteerNotification::getVolunteerId, volunteerId)
                        .orderByDesc(VolunteerNotification::getId)));
    }

    /** 未读条数，给角标用。 */
    public long unreadCount(Long volunteerId) {
        if (volunteerId == null) {
            return 0;
        }
        Long n = notificationMapper.selectCount(Wrappers.<VolunteerNotification>lambdaQuery()
                .eq(VolunteerNotification::getVolunteerId, volunteerId)
                .eq(VolunteerNotification::getIsRead, VolunteerNotification.UNREAD));
        return n == null ? 0 : n;
    }

    /**
     * 标记已读。
     *
     * <p>不存在 / 不是本人的 / 早已读过，一律返回 false 而<b>不报错</b>：
     * 前端多半是「打开详情就顺手标一次」，为这些情形抛异常只会制造无意义的失败提示。
     * 越权那一格由 SQL 的 {@code volunteer_id} 条件挡住（见 mapper），不是靠这里的返回值。</p>
     *
     * @return true = 本次确实由未读变成了已读
     */
    public boolean markRead(Long id, Long volunteerId) {
        if (id == null || volunteerId == null) {
            return false;
        }
        return notificationMapper.markRead(id, volunteerId) == 1;
    }
}
