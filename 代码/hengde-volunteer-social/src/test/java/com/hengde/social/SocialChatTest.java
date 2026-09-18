package com.hengde.social;

import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.social.constant.SocialChatCodes;
import com.hengde.social.dto.SocialChatDTOs;
import com.hengde.social.dto.SocialDTOs;
import com.hengde.social.entity.SocialChatReport;
import com.hengde.auth.constant.SanctionScope;
import com.hengde.social.dto.SocialGovDTOs;
import com.hengde.social.service.SocialBanService;
import com.hengde.social.service.SocialChatAdminService;
import com.hengde.social.service.SocialChatService;
import com.hengde.social.service.SocialKeywordService;
import com.hengde.social.service.SocialUserService;
import com.hengde.social.vo.SocialChatVOs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 私信（V4 私信批，V82）：四道门、陌生人限额、会话与未读、清空只对自己、关键词工单、投诉与处理、后台看得到全部。
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class SocialChatTest extends SocialTestSupport {

    @Autowired
    private SocialChatService chatService;
    @Autowired
    private SocialChatAdminService chatAdminService;
    @Autowired
    private SocialUserService userService;
    @Autowired
    private SocialKeywordService keywordService;
    @Autowired
    private SocialBanService banService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void gates_strangerQuota_conversationAndUnread() {
        Long alice = member();
        Long bob = member();

        // ---- 四道门 ----
        assertMessage("不能给自己发私信", () -> chatService.send(alice, alice, send("自言自语")));
        Long guest = volunteer(false, true, "游客");
        assertMessage("实名注册后才能", () -> chatService.send(guest, bob, send("我是游客")));
        assertMessage("对方现在不能接收私信", () -> chatService.send(alice, guest, send("发给游客")));
        assertMessage("说点什么", () -> chatService.send(alice, bob, send(null)));

        // ---- 陌生人限额：对方没回之前最多 3 条 ----
        for (int i = 1; i <= SocialChatCodes.STRANGER_LIMIT; i++) {
            assertTrue(chatService.send(alice, bob, send("第 " + i + " 条")) > 0);
        }
        assertMessage("对方还没有回复", () -> chatService.send(alice, bob, send("第 4 条")));

        // ---- 会话是一对人一条，两个方向同一条 ----
        List<SocialChatVOs.Conversation> aliceList = chatService.conversations(alice, page(10)).getRecords();
        SocialChatVOs.Conversation fromAlice = aliceList.stream().filter(c -> bob.equals(c.getPeerId())).findFirst().orElseThrow();
        assertEquals(0, fromAlice.getUnread(), "自己发的不算自己未读");
        assertTrue(fromAlice.isLastFromMe());
        SocialChatVOs.Conversation fromBob = chatService.conversations(bob, page(10)).getRecords().stream()
                .filter(c -> alice.equals(c.getPeerId())).findFirst().orElseThrow();
        assertEquals(fromAlice.getId(), fromBob.getId(), "两个方向是同一条会话");
        assertEquals(3, fromBob.getUnread(), "对方未读 3 条");
        assertEquals(3, chatService.unreadTotal(bob));

        // ---- 对方回了一条，限额解除；已读清零 ----
        chatService.read(bob, alice);
        assertEquals(0, chatService.unreadTotal(bob));
        assertTrue(chatService.send(bob, alice, send("我回你了")) > 0);
        assertTrue(chatService.send(alice, bob, send("第 4 条这下发得出去了")) > 0);
        assertEquals(1, chatService.unreadTotal(bob), "我发的那条让他又多一条未读");

        List<SocialChatVOs.Message> messages = chatService.messages(alice, bob, null, 20);
        assertEquals(5, messages.size());
        assertTrue(messages.get(0).isMine(), "新的在前，最后一条是我发的");
    }

    @Test
    void forbidChat_block_and_clearHistoryIsPerSide() {
        Long alice = member();
        Long bob = member();
        Long carol = member();

        // ---- 对方「禁止私信」与「不让TA看」报同一句话（分开报等于告诉他被拉黑了）----
        SocialDTOs.SettingSave setting = new SocialDTOs.SettingSave();
        setting.setForbidChat(true);
        userService.saveSetting(bob, setting);
        assertMessage("对方设置了不接收私信", () -> chatService.send(alice, bob, send("在吗")));
        setting.setForbidChat(false);
        userService.saveSetting(bob, setting);
        assertTrue(chatService.send(alice, bob, send("在吗")) > 0);

        userService.block(carol, alice);
        assertMessage("对方设置了不接收私信", () -> chatService.send(alice, carol, send("在吗")));

        // ---- 清空聊天记录只对自己；后台看得到全部 ----
        chatService.send(bob, alice, send("在的"));
        Long conversationId = chatService.conversations(alice, page(10)).getRecords().stream()
                .filter(c -> bob.equals(c.getPeerId())).findFirst().orElseThrow().getId();
        chatService.clear(alice, bob);
        assertTrue(chatService.messages(alice, bob, null, 20).isEmpty(), "我这边清空了");
        assertTrue(chatService.conversations(alice, page(10)).getRecords().stream().noneMatch(c -> bob.equals(c.getPeerId())),
                "清空之后没有新消息，会话不在我的列表里");
        assertEquals(2, chatService.messages(bob, alice, null, 20).size(), "对方那边一条不少");
        assertEquals(2, chatAdminService.messages(conversationId, page(20)).getRecords().size(), "后台看得到全部");

        // 新消息一来，会话回到我的列表，但清空之前的仍然看不到
        chatService.send(bob, alice, send("刚才说到哪了"));
        assertEquals(1, chatService.messages(alice, bob, null, 20).size());
        assertTrue(chatService.conversations(alice, page(10)).getRecords().stream().anyMatch(c -> bob.equals(c.getPeerId())));
    }

    @Test
    void keywordTicket_report_handleAndDelete() {
        Long alice = member();
        Long bob = member();
        String word = "私聊违禁" + SEQ.incrementAndGet();
        keywordService.add(admin("宣传部"), word);

        // 先落一条用户投诉（它的 id 比下面的关键词工单小）——插队要靠 source 排序，不是靠先来后到
        chatService.send(bob, alice, send("先聊一句"));
        Long earlier = chatService.report(alice, bob, report("先投诉一条，排在前面"));

        // ---- 关键词命中：消息照常送达（藏起来等于单方面切断对话），另生成一条插队工单 ----
        Long hit = chatService.send(alice, bob, send("你看这个" + word + "怎么样"));
        assertEquals(2, chatService.messages(bob, alice, null, 20).size(), "对方照样收得到（含上面那句「先聊一句」）");
        Long adminId = admin("监察部");
        List<SocialChatVOs.ChatReport> queue = chatAdminService.reports(SocialChatReport.PENDING, page(50)).getRecords();
        SocialChatVOs.ChatReport ticket = queue.stream().filter(r -> hit.equals(r.getMessageId())).findFirst().orElseThrow();
        assertEquals(SocialChatReport.SOURCE_KEYWORD, ticket.getSource());
        assertEquals(alice, ticket.getTargetId());
        assertTrue(ticket.getReason().contains(word));
        java.util.List<Long> ids = queue.stream().map(SocialChatVOs.ChatReport::getId).toList();
        assertTrue(ids.indexOf(ticket.getId()) < ids.indexOf(earlier),
                "关键词工单要插队排在更早提交的用户投诉前面，实际顺序：" + ids);

        // ---- 用户投诉：同一段对话只留一条待处理 ----
        Long reportId = chatService.report(bob, alice, report("他给我发奇怪的东西"));
        assertMessage("已经投诉过", () -> chatService.report(bob, alice, report("再投一次")));

        // ---- 处理：成立 + 删掉那条消息；CAS 只成一次 ----
        chatAdminService.handle(ticket.getId(), handle(true, "确认违规", true), adminId);
        assertTrue(chatService.messages(bob, alice, null, 20).stream().noneMatch(m -> hit.equals(m.getId())),
                "删掉之后双方都看不到那一条");
        assertTrue(chatService.messages(alice, bob, null, 20).stream().noneMatch(m -> hit.equals(m.getId())),
                "发的人自己也看不到");
        assertTrue(chatAdminService.messages(ticket.getConversationId(), page(20)).getRecords().get(0).isDeleted(),
                "后台仍列得出来，标成已删除");
        assertMessage("刚被别人处理过", () -> chatAdminService.handle(ticket.getId(), handle(false, "再处理一次", false), adminId));
        chatAdminService.handle(reportId, handle(false, "只是普通聊天", false), adminId);
        assertTrue(chatAdminService.reports(SocialChatReport.PENDING, page(50)).getRecords().stream()
                .noneMatch(r -> reportId.equals(r.getId())));
    }

    @Test
    void chatBan_blocksSendingButNotReceiving() {
        Long alice = member();
        Long bob = member();
        Long adminId = admin("监察部");
        chatService.send(bob, alice, send("先聊两句"));

        // 走真实入口开单：禁言能开「禁止私信」这一档（BAN_SCOPES 含 7），限制落在 volunteer_sanction 上
        SocialGovDTOs.BanSave ban = new SocialGovDTOs.BanSave();
        ban.setVolunteerId(alice);
        ban.setScope(SanctionScope.COMMUNITY_CHAT);
        ban.setDays(3);
        ban.setReason("用例禁言");
        Long banId = banService.ban(adminId, ban);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM volunteer_sanction WHERE volunteer_id = ? AND scope = 7 "
                + "AND status = 1", Integer.class, alice), "禁言要真的写成一条处置");

        assertMessage("禁止私信", () -> chatService.send(alice, bob, send("我还能说话吗")));
        assertTrue(chatService.send(bob, alice, send("我还能发给你")) > 0, "禁言只挡发，不挡收");
        assertFalse(chatService.messages(alice, bob, null, 20).isEmpty(), "他照样看得到别人发来的");

        banService.lift(adminId, banId, "用例解除");
        assertTrue(chatService.send(alice, bob, send("解除了")) > 0);

        // 「限制发布社区」蕴含禁止私信——蕴含关系只写在 SanctionScope.implying 一处，这条用例是它的证据
        // （只开第 7 档的用例证明不了蕴含：把 COMMUNITY_CHAT 从蕴含表里摘掉，那条照样绿）
        SocialGovDTOs.BanSave wide = new SocialGovDTOs.BanSave();
        wide.setVolunteerId(alice);
        wide.setScope(SanctionScope.COMMUNITY);
        wide.setDays(3);
        wide.setReason("用例：限制发布社区");
        Long wideId = banService.ban(adminId, wide);
        // 报错文案说的是那条处置自己的能力域（「限制发布社区」），动作名才是「发私信」——蕴含关系正体现在这里
        assertMessage("暂不能发私信", () -> chatService.send(alice, bob, send("限制发布社区之后还能私信吗")));
        assertTrue(chatService.send(bob, alice, send("我还是发得出去")) > 0, "挡的是发不是收");
        banService.lift(adminId, wideId, "用例解除");
        assertTrue(chatService.send(alice, bob, send("又能发了")) > 0);
    }

    // ================= 造数 =================

    private static SocialChatDTOs.Send send(String content) {
        SocialChatDTOs.Send d = new SocialChatDTOs.Send();
        d.setContent(content);
        return d;
    }

    private static SocialChatDTOs.Report report(String reason) {
        SocialChatDTOs.Report d = new SocialChatDTOs.Report();
        d.setReason(reason);
        return d;
    }

    private static SocialChatDTOs.Handle handle(boolean valid, String note, boolean deleteMessage) {
        SocialChatDTOs.Handle d = new SocialChatDTOs.Handle();
        d.setValid(valid);
        d.setNote(note);
        d.setDeleteMessage(deleteMessage);
        return d;
    }
}
