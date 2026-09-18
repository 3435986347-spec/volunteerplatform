package com.hengde.social;

import com.hengde.auth.entity.VolunteerNotification;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.social.constant.SocialGovCodes;
import com.hengde.social.dao.SocialPostMapper;
import com.hengde.social.dto.SocialGovDTOs;
import com.hengde.social.service.SocialInteractionFeedService;
import com.hengde.social.service.SocialInteractionService;
import com.hengde.social.service.SocialPostService;
import com.hengde.social.service.SocialReportService;
import com.hengde.social.service.SocialReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 社区治理的并发（全部<b>必跑</b>）：两个同级审核员同时通过只成一个、同一人连点举报只留一条待处理、同一对象同时成立与不成立只成一个、
 * 汇总提示同时跑多轮每人只提示一次。
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class SocialGovConcurrencyTest extends SocialTestSupport {

    @Autowired
    private SocialPostService postService;
    @Autowired
    private SocialReviewService reviewService;
    @Autowired
    private SocialReportService reportService;
    @Autowired
    private SocialInteractionService interactionService;
    @Autowired
    private SocialInteractionFeedService feedService;
    @Autowired
    private SocialPostMapper postMapper;
    @Autowired
    private JdbcTemplate jdbc;

    /** 两级审核下才测得到 CAS 里「还停在上一级」那个条件——一级审核时第一个人一通过帖子就是「已通过」，状态条件就把第二个人挡住了。 */
    @Test
    void twoReviewersApproveTheSameLevel_onlyOneWins() throws Exception {
        Long setter = admin("理事会");
        reviewService.setLevels(setter, 2);
        try {
            approveRaces();
        } finally {
            reviewService.setLevels(setter, 1);
        }
    }

    private void approveRaces() throws Exception {
        for (int round = 0; round < 10; round++) {
            Long post = postService.publish(member(), text("抢着审 " + round));
            Long a = admin("组织部");
            Long b = admin("监察部");
            int ok = runAll(List.of(() -> {
                reviewService.approve(a, true, post);
                return null;
            }, () -> {
                reviewService.approve(b, true, post);
                return null;
            }));
            assertEquals(1, ok, "同一级只成一个");
            assertEquals(1, postMapper.selectById(post).getReviewLevel());
            assertEquals(0, postMapper.selectById(post).getReviewStatus(), "两级里过了一级，还在审核中");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM social_review_log WHERE post_id = ?", Integer.class, post));
        }
    }

    @Test
    void samePersonReportsEightTimes_onePendingRow() throws Exception {
        Long post = postService.publish(member(), text("被连点举报"));
        Long reporter = member();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> reportService.report(reporter, SocialGovernanceTest.report(SocialGovCodes.REPORT_TARGET_POST, post, "连点")));
        }
        assertEquals(1, runAll(tasks));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM social_report WHERE reporter_id = ? AND target_id = ? AND status = 0",
                Integer.class, reporter, post));
    }

    @Test
    void upholdAndDismissTheSameTarget_onlyOneWins() throws Exception {
        for (int round = 0; round < 10; round++) {
            Long post = postService.publish(member(), text("同时处理 " + round));
            Long r1 = reportService.report(member(), SocialGovernanceTest.report(SocialGovCodes.REPORT_TARGET_POST, post, "甲"));
            Long r2 = reportService.report(member(), SocialGovernanceTest.report(SocialGovCodes.REPORT_TARGET_POST, post, "乙"));
            SocialGovDTOs.ReportHandle hide = new SocialGovDTOs.ReportHandle();
            hide.setAction(SocialGovCodes.ACTION_HIDE);
            int ok = runAll(List.of(() -> {
                reportService.uphold(admin("监察部"), r1, hide);
                return null;
            }, () -> {
                reportService.dismiss(admin("监察部"), r2, new SocialGovDTOs.ReportHandle());
                return null;
            }));
            assertEquals(1, ok, "同一对象只结一次案");
            List<Integer> statuses = jdbc.queryForList("SELECT DISTINCT status FROM social_report WHERE target_id = ? AND target_type = 1",
                    Integer.class, post);
            assertEquals(1, statuses.size(), "两条举报是同一个结论：" + statuses);
            assertEquals(statuses.get(0) == SocialGovCodes.REPORT_UPHELD ? 1 : 0, postMapper.selectById(post).getAdminHidden(),
                    "只有成立的那一方才隐藏");
        }
    }

    @Test
    void digestRunsConcurrently_eachRecipientNotifiedOnce() throws Exception {
        List<Long> authors = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Long author = member();
            authors.add(author);
            interactionService.like(member(), postService.publish(author, text("等汇总 " + i)));
        }
        List<Callable<Object>> runs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            runs.add(feedService::digestOnce);
        }
        runAll(runs);
        for (Long author : authors) {
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM volunteer_notification WHERE volunteer_id = ? AND type = ?",
                    Integer.class, author, VolunteerNotification.TYPE_SOCIAL_INTERACTIONS), "志愿者 " + author + " 只提示一次");
        }
    }

    /** 水位 CAS 是任务锁之外的第二道：拿着同一个旧水位推进两次，只有第一次发提示（锁挡住了重叠的任务，这条用例单独钉住 CAS）。 */
    @Test
    void digestRecipient_staleWatermarkDoesNotNotifyTwice() {
        Long author = member();
        interactionService.like(member(), postService.publish(author, text("水位")));
        long maxId = jdbc.queryForObject("SELECT MAX(id) FROM social_interaction WHERE recipient_id = ?", Long.class, author);
        long before = jdbc.queryForObject("SELECT COALESCE(MAX(last_digest_id), 0) FROM social_interaction_digest WHERE volunteer_id = ?",
                Long.class, author);
        assertEquals(true, feedService.digestRecipient(author, before, maxId, 1));
        assertEquals(false, feedService.digestRecipient(author, before, maxId, 1), "水位已经推进过，旧水位推不动");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM volunteer_notification WHERE volunteer_id = ? AND type = ?",
                Integer.class, author, VolunteerNotification.TYPE_SOCIAL_INTERACTIONS));
    }

    /** @return 成功（没抛业务异常）的个数 */
    private static int runAll(List<Callable<Object>> tasks) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        int ok = 0;
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> t : tasks) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return t.call();
                }));
            }
            for (Future<Object> f : futures) {
                try {
                    f.get(60, TimeUnit.SECONDS);
                    ok++;
                } catch (ExecutionException e) {
                    assertInstanceOf(BusinessException.class, e.getCause(), "只允许业务拒绝：" + e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return ok;
    }
}
