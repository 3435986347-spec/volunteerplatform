package com.hengde.social;

import com.hengde.auth.constant.SanctionScope;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.social.constant.SocialCodes;
import com.hengde.social.constant.SocialGovCodes;
import com.hengde.social.dto.SocialGovDTOs;
import com.hengde.social.service.SocialAdminService;
import com.hengde.social.service.SocialBanService;
import com.hengde.social.service.SocialCommentService;
import com.hengde.social.service.SocialInteractionFeedService;
import com.hengde.social.service.SocialInteractionService;
import com.hengde.social.service.SocialKeywordService;
import com.hengde.social.service.SocialPostService;
import com.hengde.social.service.SocialReportService;
import com.hengde.social.service.SocialReviewService;
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
 * 社区治理压测（{@code @Tag("stress")}）：两级审核下 30 人发帖（部分命中关键词）/ 点赞 / 评论 / 举报，4 个审核员乱序通过 / 驳回，
 * 管理员隐藏 / 置顶，处理举报，禁言与解除，汇总提示同时在跑，读者不停刷帖子流。
 *
 * <p>断言：每条帖子的通过记录条数＝已通过级数、且出自不同的人；已通过的恰好过了两级；命中关键词还没审完的帖子从不出现在别人的帖子流里；
 * 没删的帖子点赞 / 评论计数与行数相等；除业务拒绝外零异常。</p>
 *
 * @author hengde
 */
@Tag("stress")
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class SocialGovStressTest extends SocialTestSupport {

    @Autowired
    private SocialPostService postService;
    @Autowired
    private SocialInteractionService interactionService;
    @Autowired
    private SocialCommentService commentService;
    @Autowired
    private SocialKeywordService keywordService;
    @Autowired
    private SocialReviewService reviewService;
    @Autowired
    private SocialAdminService adminService;
    @Autowired
    private SocialReportService reportService;
    @Autowired
    private SocialBanService banService;
    @Autowired
    private SocialInteractionFeedService feedService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void governanceChurn() throws Exception {
        String word = "压测敏感词" + SEQ.incrementAndGet();
        Long boss = admin("理事会");
        keywordService.add(boss, word);
        List<Long> reviewers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Long r = admin("审核部" + i);
            reviewService.addReviewer(boss, r, 1 + i % 2);
            reviewers.add(r);
        }
        reviewService.setLevels(boss, 2);
        try {
            List<Long> people = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                people.add(member());
            }
            List<Long> posts = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < 20; i++) {
                posts.add(postService.publish(people.get(i), text(i % 4 == 0 ? "初始 " + word : "初始帖 " + i)));
            }
            Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
            List<String> failures = Collections.synchronizedList(new ArrayList<>());
            Random rnd = new Random(47L);
            List<Runnable> tasks = new ArrayList<>();
            for (int i = 0; i < 1200; i++) {
                int roll = rnd.nextInt(100);
                Long who = people.get(rnd.nextInt(people.size()));
                Long reviewer = reviewers.get(rnd.nextInt(reviewers.size()));
                int pick = rnd.nextInt(1_000_000);
                boolean hitWord = rnd.nextInt(5) == 0;
                int seq = i;
                tasks.add(() -> {
                    Long post;
                    synchronized (posts) {
                        post = posts.get(pick % posts.size());
                    }
                    String kind = roll < 12 ? "发帖" : roll < 25 ? "点赞" : roll < 35 ? "评论" : roll < 40 ? "举报" : roll < 58 ? "审核通过"
                            : roll < 62 ? "驳回" : roll < 66 ? "隐藏切换" : roll < 69 ? "处理举报" : roll < 72 ? "禁言" : roll < 74 ? "汇总"
                            : "刷帖子流";
                    try {
                        switch (kind) {
                            case "发帖" -> posts.add(postService.publish(who, text(hitWord ? "带 " + word + " " + seq : "压测 " + seq)));
                            case "点赞" -> interactionService.like(who, post);
                            case "评论" -> commentService.comment(who, post, commentOf("评 " + seq, null));
                            case "举报" -> reportService.report(who, SocialGovernanceTest.report(SocialGovCodes.REPORT_TARGET_POST, post, "压测举报"));
                            case "审核通过" -> reviewService.approve(reviewer, false, post);
                            case "驳回" -> reviewService.reject(reviewer, false, post, "压测驳回");
                            case "隐藏切换" -> adminService.setHidden(post, seq % 2 == 0);
                            case "处理举报" -> {
                                Long rid = jdbc.query("SELECT id FROM social_report WHERE status = 0 ORDER BY RAND() LIMIT 1",
                                        rs -> rs.next() ? rs.getLong(1) : null);
                                if (rid == null) {
                                    throw new BusinessException("没有待处理的举报");
                                }
                                if (seq % 2 == 0) {
                                    reportService.dismiss(boss, rid, new SocialGovDTOs.ReportHandle());
                                } else {
                                    SocialGovDTOs.ReportHandle h = new SocialGovDTOs.ReportHandle();
                                    h.setAction(SocialGovCodes.ACTION_NONE);
                                    reportService.uphold(boss, rid, h);
                                }
                            }
                            case "禁言" -> {
                                Long banId = banService.ban(boss, SocialGovernanceTest.ban(who, SanctionScope.COMMUNITY_LIKE, 1));
                                banService.lift(boss, banId, "压测解除");
                            }
                            case "汇总" -> feedService.digestOnce();
                            default -> {
                                for (SocialVOs.Post p : postService.feed(who, "latest", null, page(30)).getRecords()) {
                                    if (!p.isMine()) {
                                        Integer[] row = jdbc.query("SELECT keyword_hit, review_status FROM social_post WHERE id = ?",
                                                rs -> rs.next() ? new Integer[]{rs.getInt(1), rs.getInt(2)} : null, p.getId());
                                        if (row != null && row[0] == 1 && row[1] == SocialCodes.REVIEW_PENDING) {
                                            failures.add("帖子流里出现了别人还没审完的关键词帖：" + p.getId());
                                        }
                                    }
                                }
                            }
                        }
                        outcomes.computeIfAbsent(kind + "·成功", k -> new AtomicInteger()).incrementAndGet();
                    } catch (BusinessException e) {
                        outcomes.computeIfAbsent(kind + "·拒绝·" + e.getMessage().replaceAll("「[^」]*」|\\d+|（.*）", "…"),
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
            System.out.println("==== 社区治理压测：" + tasks.size() + " 次 / 16 线程 / " + (System.currentTimeMillis() - t0)
                    + " ms ====\n  " + new TreeMap<>(outcomes));
            assertTrue(failures.isEmpty(), "除业务拒绝外不应有任何异常：" + failures);

            List<Map<String, Object>> badReview = jdbc.queryForList("SELECT p.id, p.review_status, p.review_level, "
                    + "(SELECT COUNT(*) FROM social_review_log l WHERE l.post_id = p.id AND l.action = 1) AS approvals, "
                    + "(SELECT COUNT(DISTINCT l.admin_user_id) FROM social_review_log l WHERE l.post_id = p.id AND l.action = 1) AS people "
                    + "FROM social_post p WHERE p.author_type = 1 AND p.id >= ? "
                    + "HAVING approvals <> p.review_level OR people <> approvals OR (p.review_status = 1 AND p.review_level <> 2)", posts.get(0));
            assertEquals(List.of(), badReview, "审核记录与级数对得上、每级不同的人、通过的恰好两级");
            List<Map<String, Object>> drift = jdbc.queryForList("SELECT p.id FROM social_post p WHERE p.is_deleted = 0 AND p.id >= ? AND ("
                    + "p.like_count <> (SELECT COUNT(*) FROM social_post_like l WHERE l.post_id = p.id) OR "
                    + "p.comment_count <> (SELECT COUNT(*) FROM social_comment c WHERE c.post_id = p.id AND c.is_deleted = 0))", posts.get(0));
            assertEquals(List.of(), drift, "计数与行数对得上");
        } finally {
            reviewService.setLevels(boss, 1);
        }
    }
}
