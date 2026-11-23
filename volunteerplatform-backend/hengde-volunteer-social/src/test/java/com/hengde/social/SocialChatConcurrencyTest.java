package com.hengde.social;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.social.constant.SocialChatCodes;
import com.hengde.social.dto.SocialChatDTOs;
import com.hengde.social.service.SocialChatService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 私信的并发（前三条<b>必跑</b>）与压测（{@code @Tag("stress")}）。
 *
 * <p>① 两个人同时给对方发第一条：只该建出<b>一条</b>会话——承重的是 {@code uk_pair} 与「撞键就读回赢家」，
 * 先查再插在并发下必然建出两条（(A,B) 与 (B,A) 各一条，未读数与最后一条各记一半）。
 * ② 同一个人 10 条同时发：对方未读数恰好 +10——承重的是 {@code SET x = x + 1} 这种读改写合一的语句，
 * 「查出来再写回去」会互相覆盖。③ 陌生人限额下 8 条同时发：最多成 3 条。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class SocialChatConcurrencyTest extends SocialTestSupport {

    @Autowired
    private SocialChatService chatService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void twoPeopleOpeningTheSameConversationAtOnceGetOneRow() throws Exception {
        Long alice = member();
        Long bob = member();
        CyclicBarrier gate = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> a = pool.submit(() -> {
                gate.await(20, TimeUnit.SECONDS);
                return chatService.send(alice, bob, send("我先说"));
            });
            Future<Long> b = pool.submit(() -> {
                gate.await(20, TimeUnit.SECONDS);
                return chatService.send(bob, alice, send("我也先说"));
            });
            assertTrue(a.get(30, TimeUnit.SECONDS) > 0);
            assertTrue(b.get(30, TimeUnit.SECONDS) > 0);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM social_conversation WHERE small_id = ? AND large_id = ?",
                Integer.class, Math.min(alice, bob), Math.max(alice, bob)), "一对人只该有一条会话");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM social_message WHERE (sender_id = ? AND receiver_id = ?) "
                + "OR (sender_id = ? AND receiver_id = ?)", Integer.class, alice, bob, bob, alice), "两条消息都在");
    }

    @Test
    void tenConcurrentMessagesCountUnreadExactlyOnce() throws Exception {
        Long alice = member();
        Long bob = member();
        chatService.send(bob, alice, send("你先说"));     // 让 alice 不受陌生人限额约束
        chatService.read(alice, bob);
        int n = 10;
        CyclicBarrier gate = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                futures.add(pool.submit(() -> {
                    gate.await(20, TimeUnit.SECONDS);
                    return chatService.send(alice, bob, send("连发第 " + k + " 条"));
                }));
            }
            for (Future<Long> f : futures) {
                assertTrue(f.get(30, TimeUnit.SECONDS) > 0);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(n, chatService.unreadTotal(bob) - 0, "未读数不能被并发覆盖掉");
        Long conversationId = jdbc.queryForObject("SELECT id FROM social_conversation WHERE small_id = ? AND large_id = ?",
                Long.class, Math.min(alice, bob), Math.max(alice, bob));
        assertEquals(n, jdbc.queryForObject("SELECT small_sent + large_sent - 1 FROM social_conversation WHERE id = ?",
                Integer.class, conversationId), "已发条数同理");
        assertEquals(jdbc.queryForObject("SELECT MAX(id) FROM social_message WHERE conversation_id = ?", Long.class, conversationId),
                jdbc.queryForObject("SELECT last_message_id FROM social_conversation WHERE id = ?", Long.class, conversationId),
                "会话上的最后一条要是真的最后一条");
    }

    @Test
    void strangerQuotaHoldsUnderConcurrentSends() throws Exception {
        Long alice = member();
        Long bob = member();
        int n = 8;
        CyclicBarrier gate = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        AtomicInteger ok = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                futures.add(pool.submit(() -> {
                    gate.await(20, TimeUnit.SECONDS);
                    try {
                        chatService.send(alice, bob, send("同时发第 " + k + " 条"));
                        ok.incrementAndGet();
                    } catch (BusinessException e) {
                        assertTrue(e.getMessage().contains("对方还没有回复"), e.getMessage());
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(ok.get() <= SocialChatCodes.STRANGER_LIMIT,
                "陌生人限额被并发绕过了，成了 " + ok.get() + " 条");
        assertTrue(ok.get() >= 1, "至少要成一条");
    }

    @Tag("stress")
    @Test
    void chatChurn_invariantsHold() throws Exception {
        int people = 20;
        List<Long> members = new ArrayList<>();
        for (int i = 0; i < people; i++) {
            members.add(member());
        }
        Map<String, AtomicInteger> tally = new ConcurrentHashMap<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        AtomicInteger seq = new AtomicInteger();
        int threads = 16;
        int operations = 900;
        CyclicBarrier gate = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long started = System.currentTimeMillis();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    Random rnd = new Random(Thread.currentThread().getId() * 37 + 11);
                    gate.await(30, TimeUnit.SECONDS);
                    while (true) {
                        int n = seq.incrementAndGet();
                        if (n > operations) {
                            return null;
                        }
                        Long me = members.get(rnd.nextInt(people));
                        Long peer = members.get(rnd.nextInt(people));
                        if (me.equals(peer)) {
                            continue;
                        }
                        int dice = rnd.nextInt(100);
                        try {
                            if (dice < 55) {
                                chatService.send(me, peer, send("压测消息 " + n));
                                bump(tally, "发消息");
                            } else if (dice < 70) {
                                chatService.read(me, peer);
                                bump(tally, "已读");
                            } else if (dice < 78) {
                                chatService.clear(me, peer);
                                bump(tally, "清空");
                            } else if (dice < 84) {
                                chatService.report(me, peer, report("压测投诉 " + n));
                                bump(tally, "投诉");
                            } else if (dice < 92) {
                                chatService.messages(me, peer, null, 20);
                                bump(tally, "翻记录");
                            } else {
                                chatService.conversations(me, page(20));
                                chatService.unreadTotal(me);
                                bump(tally, "看列表");
                            }
                        } catch (BusinessException e) {
                            bump(tally, "拒绝:" + e.getMessage());
                        } catch (Throwable e) {
                            unexpected.add(e);
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(300, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(unexpected.isEmpty(), "压测中出现非业务异常：" + unexpected);

        Integer dupPairs = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT small_id, large_id FROM social_conversation "
                + "GROUP BY small_id, large_id HAVING COUNT(*) > 1) t", Integer.class);
        assertEquals(0, dupPairs, "一对人只能有一条会话");
        Integer badLast = jdbc.queryForObject("SELECT COUNT(*) FROM social_conversation c WHERE c.last_message_id IS NOT NULL "
                + "AND c.last_message_id <> (SELECT MAX(m.id) FROM social_message m WHERE m.conversation_id = c.id)",
                Integer.class);
        assertEquals(0, badLast, "会话上的最后一条必须是真的最后一条");
        Integer badSent = jdbc.queryForObject("SELECT COUNT(*) FROM social_conversation c WHERE "
                + "c.small_sent <> (SELECT COUNT(*) FROM social_message m WHERE m.conversation_id = c.id AND m.sender_id = c.small_id) "
                + "OR c.large_sent <> (SELECT COUNT(*) FROM social_message m WHERE m.conversation_id = c.id AND m.sender_id = c.large_id)",
                Integer.class);
        assertEquals(0, badSent, "已发条数与消息表必须对得上（并发覆盖就会对不上）");
        Integer negativeUnread = jdbc.queryForObject(
                "SELECT COUNT(*) FROM social_conversation WHERE small_unread < 0 OR large_unread < 0", Integer.class);
        assertEquals(0, negativeUnread);
        Integer overUnread = jdbc.queryForObject("SELECT COUNT(*) FROM social_conversation c WHERE "
                + "c.small_unread > (SELECT COUNT(*) FROM social_message m WHERE m.conversation_id = c.id AND m.receiver_id = c.small_id) "
                + "OR c.large_unread > (SELECT COUNT(*) FROM social_message m WHERE m.conversation_id = c.id AND m.receiver_id = c.large_id)",
                Integer.class);
        assertEquals(0, overUnread, "未读数不该超过对方发来的条数");
        Integer dupReports = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT reporter_id, conversation_id FROM social_chat_report "
                + "WHERE status = 0 AND reporter_id IS NOT NULL GROUP BY reporter_id, conversation_id HAVING COUNT(*) > 1) t",
                Integer.class);
        assertEquals(0, dupReports, "同一个人对同一段对话只该有一条待处理投诉");
        System.out.println("[压测] 私信：" + operations + " 次操作 / " + threads + " 线程 / "
                + (System.currentTimeMillis() - started) + " ms，分布 " + new java.util.TreeMap<>(tally));
    }

    private static void bump(Map<String, AtomicInteger> tally, String key) {
        tally.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

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
}
