package com.hengde.auth;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.entity.VolunteerNotification;
import com.hengde.auth.service.NotificationService;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站内提示（V37，Row 41 F「志愿者会收到提示」）的读写与越权边界。<b>需本机 Docker。</b>
 *
 * <p>⚠️ <b>本类断言 {@code getRecords()} 而不是 {@code getTotal()}</b>，这不是随手写的：
 * 分页插件 {@code MybatisPlusConfig} 只注册在 <b>api</b> 模块，而 auth 的测试上下文
 * （{@code TestAuthApplication}）够不着它。没有 {@code PaginationInnerInterceptor} 时
 * {@code selectPage} 照常返回全部记录、但 {@code total} 恒为 0——断言 total 等于在断言
 * 一个「本模块根本没装的插件」。生产走 api，分页是正常的。
 * 这个缺口不是本批引入的，也不止影响本类（所有模块的分页用例都在无插件状态下跑），
 * 已单独记为待办，不在奖惩这一批里顺手改动全局构建结构。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class NotificationServiceTest {

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private NotificationService notificationService;
    @Autowired
    private VolunteerMapper volunteerMapper;

    @Test
    void notify_thenListedNewestFirst_andCounted() {
        Long vid = insertVolunteer();
        notificationService.notify(vid, VolunteerNotification.TYPE_REWARD_PUNISH_APPROVED,
                "第一条", "内容一", VolunteerNotification.BIZ_REWARD_PUNISH, 11L);
        notificationService.notify(vid, VolunteerNotification.TYPE_APPEAL_HANDLED,
                "第二条", "内容二", VolunteerNotification.BIZ_REWARD_PUNISH, 11L);

        PageResult<VolunteerNotification> page =
                notificationService.myNotifications(vid, new PageQuery());
        assertEquals(2, page.getRecords().size());
        assertEquals("第二条", page.getRecords().get(0).getTitle(), "新的在前");
        assertEquals(2, notificationService.unreadCount(vid), "刚发出的都应是未读");

        VolunteerNotification first = page.getRecords().get(0);
        assertEquals(VolunteerNotification.BIZ_REWARD_PUNISH, first.getBizType(), "关联业务要带上，前端据此跳详情");
        assertEquals(11L, first.getBizId());
    }

    /**
     * 标记已读是 CAS：{@code read_time} 应当是<b>第一次</b>看到的时刻。
     *
     * <p>不判未读的话，每刷一次都会把它覆盖成最后一次，这个字段也就没有意义了。
     * 去掉 mapper 里的 {@code AND is_read = 0}，第二次调用会返回 true，本用例红。</p>
     */
    @Test
    void markRead_isIdempotentAndKeepsTheFirstReadTime() {
        Long vid = insertVolunteer();
        notificationService.notify(vid, VolunteerNotification.TYPE_REWARD_PUNISH_APPROVED,
                "标题", "正文", null, null);
        Long id = onlyNotification(vid).getId();

        assertTrue(notificationService.markRead(id, vid), "第一次应当由未读变已读");
        LocalDateTime firstReadAt = onlyNotification(vid).getReadTime();
        assertNotNull(firstReadAt);

        assertFalse(notificationService.markRead(id, vid), "第二次不该再算一次「变成已读」");
        assertEquals(firstReadAt, onlyNotification(vid).getReadTime(), "read_time 不该被后续访问覆盖");
        assertEquals(0, notificationService.unreadCount(vid));
    }

    /**
     * <b>不能标记别人的提示。</b>
     *
     * <p>去掉 mapper 里的 {@code AND volunteer_id = #{volunteerId}}，任何人传一个 id
     * 就能把别人的提示标成已读——对方只是发现角标自己少了一个，没有任何征兆。</p>
     */
    @Test
    void markRead_cannotTouchSomeoneElsesNotification() {
        Long owner = insertVolunteer();
        Long stranger = insertVolunteer();
        notificationService.notify(owner, VolunteerNotification.TYPE_REWARD_PUNISH_APPROVED,
                "标题", "正文", null, null);
        Long id = onlyNotification(owner).getId();

        assertFalse(notificationService.markRead(id, stranger), "别人的提示不该被标记");
        assertEquals(1, notificationService.unreadCount(owner), "所有者那边必须还是未读");
    }

    /** 只看得到自己的：列表恒按登录态取收件人，这里直接验数据边界。 */
    @Test
    void listing_isScopedToTheOwner() {
        Long a = insertVolunteer();
        Long b = insertVolunteer();
        notificationService.notify(a, VolunteerNotification.TYPE_REWARD_PUNISH_APPROVED,
                "甲的提示", "正文", null, null);

        assertTrue(notificationService.myNotifications(b, new PageQuery()).getRecords().isEmpty());
        assertEquals(0, notificationService.unreadCount(b));
    }

    private VolunteerNotification onlyNotification(Long vid) {
        PageResult<VolunteerNotification> page =
                notificationService.myNotifications(vid, new PageQuery());
        assertEquals(1, page.getRecords().size(), "夹具自检：该志愿者应恰好有一条提示");
        return page.getRecords().get(0);
    }

    private Long insertVolunteer() {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_notify_" + System.nanoTime() + "_" + SEQ.incrementAndGet());
        v.setRealName("提示用例");
        v.setStatus(UserStatus.NORMAL);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }
}
