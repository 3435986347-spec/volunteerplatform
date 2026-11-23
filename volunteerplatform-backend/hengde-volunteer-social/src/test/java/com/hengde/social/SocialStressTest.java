package com.hengde.social;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.social.constant.SocialCodes;
import com.hengde.social.service.SocialCommentService;
import com.hengde.social.service.SocialInteractionService;
import com.hengde.social.service.SocialPostService;
import com.hengde.social.service.SocialUserService;
import com.hengde.social.vo.SocialVOs;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 社区压测（{@code @Tag("stress")}）：40 人 16 线程乱序发帖 / 改帖 / 删帖 / 点赞 / 取消 / 评论 / 删评论 / 关注 / 取关 / 拉黑 /
 * 看帖子流（最新 / 最热 / 关注）/ 看详情 / 分享。
 *
 * <p>断言：没删的帖子点赞量＝点赞行数、评论量＝没删的评论行数；任何人刷到的帖子流里没有别人的「隐藏」帖；除业务拒绝外零异常
 * （死锁、锁超时都算失败）。</p>
 *
 * @author hengde
 */
@Tag("stress")
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class SocialStressTest extends SocialTestSupport {

    @Autowired
    private SocialPostService postService;
    @Autowired
    private SocialInteractionService interactionService;
    @Autowired
    private SocialCommentService commentService;
    @Autowired
    private SocialUserService userService;
    @Autowired
    private JdbcTemplate jdbc;

    /** 这个人自己的一条没删的帖子（没有就退回随机那条，让它自然被拒）。 */
    private Long ownPost(Long who, Long fallback) {
        Long mine = jdbc.query("SELECT id FROM social_post WHERE author_type = 1 AND author_id = ? AND is_deleted = 0 ORDER BY RAND() LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, who);
        return mine == null ? fallback : mine;
    }

    @Test
    void communityChurn_keepsCountersAndVisibilitySound() throws Exception {
        List<Long> people = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            people.add(member());
        }
        List<Long> posts = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 30; i++) {
            posts.add(postService.publish(people.get(i % people.size()), withVisibility("初始帖 " + i, i % 5 < 3 ? 0 : i % 4)));
        }
        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        Random rnd = new Random(31L);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            int roll = rnd.nextInt(100);
            Long who = people.get(rnd.nextInt(people.size()));
            Long other = people.get(rnd.nextInt(people.size()));
            int pick = rnd.nextInt(1_000_000);
            int visibility = rnd.nextInt(10) < 7 ? 0 : 1 + rnd.nextInt(3);   // 七成公开，让点赞 / 评论有足够的成功路径
            int seq = i;
            tasks.add(() -> {
                Long post;
                synchronized (posts) {
                    post = posts.get(pick % posts.size());
                }
                String kind = roll < 8 ? "发帖" : roll < 11 ? "改帖" : roll < 13 ? "删帖" : roll < 33 ? "点赞" : roll < 41 ? "取消点赞"
                        : roll < 53 ? "评论" : roll < 57 ? "删评论" : roll < 63 ? "关注" : roll < 66 ? "取关" : roll < 68 ? "拉黑"
                        : roll < 80 ? "刷帖子流" : roll < 94 ? "看详情" : "分享";
                try {
                    switch (kind) {
                        case "发帖" -> posts.add(postService.publish(who, withVisibility("压测帖 " + seq, visibility)));
                        case "改帖" -> postService.update(who, ownPost(who, post), withVisibility("改过 " + seq, visibility));
                        case "删帖" -> postService.delete(who, ownPost(who, post));
                        case "点赞" -> interactionService.like(who, post);
                        case "取消点赞" -> interactionService.unlike(who, post);
                        case "评论" -> commentService.comment(who, post, commentOf("压测评论 " + seq, null));
                        case "删评论" -> {
                            // 挑一条我能删的：我写的，或我帖子下的
                            Long c = jdbc.query("SELECT c.id FROM social_comment c JOIN social_post p ON p.id = c.post_id "
                                            + "WHERE c.is_deleted = 0 AND ((c.author_type = 1 AND c.author_id = ?) "
                                            + "OR (p.author_type = 1 AND p.author_id = ?)) ORDER BY RAND() LIMIT 1",
                                    rs -> rs.next() ? rs.getLong(1) : null, who, who);
                            if (c == null) {
                                throw new BusinessException("没有评论可删");
                            }
                            commentService.delete(who, c);
                        }
                        case "关注" -> userService.follow(who, other);
                        case "取关" -> userService.unfollow(who, other);
                        case "拉黑" -> {
                            userService.block(who, other);
                            userService.unblock(who, other);
                        }
                        case "刷帖子流" -> {
                            String tab = roll % 3 == 0 ? "hot" : roll % 3 == 1 ? "following" : "latest";
                            for (SocialVOs.Post p : postService.feed(who, tab, null, page(20)).getRecords()) {
                                if (p.getVisibility() == SocialCodes.VISIBLE_SELF && !p.isMine()) {
                                    failures.add("帖子流里出现了别人的隐藏帖：viewer=" + who + " post=" + p.getId());
                                }
                            }
                        }
                        case "看详情" -> postService.detail(who, post);
                        default -> postService.share(who, post);
                    }
                    outcomes.computeIfAbsent(kind + "·成功", k -> new AtomicInteger()).incrementAndGet();
                } catch (BusinessException e) {
                    outcomes.computeIfAbsent(kind + "·拒绝·" + e.getMessage().replaceAll("「[^」]*」|\\d+", "…"),
                            k -> new AtomicInteger()).incrementAndGet();
                } catch (Exception e) {
                    failures.add(kind + " → " + e);
                }
            });
        }
        long t0 = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable r : tasks) {
            futures.add(pool.submit(r));
        }
        for (Future<?> f : futures) {
            f.get(300, TimeUnit.SECONDS);
        }
        pool.shutdown();
        System.out.println("==== 社区压测：" + tasks.size() + " 次 / 16 线程 / " + (System.currentTimeMillis() - t0)
                + " ms ====\n  " + new TreeMap<>(outcomes));
        assertTrue(failures.isEmpty(), "除业务拒绝外不应有任何异常：" + failures);

        List<Map<String, Object>> drift = jdbc.queryForList("SELECT p.id, p.like_count, p.comment_count, "
                + "(SELECT COUNT(*) FROM social_post_like l WHERE l.post_id = p.id) AS likes, "
                + "(SELECT COUNT(*) FROM social_comment c WHERE c.post_id = p.id AND c.is_deleted = 0) AS comments "
                + "FROM social_post p WHERE p.is_deleted = 0 HAVING p.like_count <> likes OR p.comment_count <> comments");
        assertEquals(List.of(), drift, "没删的帖子计数与行数对得上");
    }
}
