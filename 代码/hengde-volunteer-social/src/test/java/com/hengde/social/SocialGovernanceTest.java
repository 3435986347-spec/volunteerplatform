package com.hengde.social;

import com.hengde.auth.constant.SanctionScope;
import com.hengde.auth.entity.VolunteerNotification;
import com.hengde.auth.service.SanctionQueryService;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.social.constant.SocialGovCodes;
import com.hengde.social.dao.SocialPostMapper;
import com.hengde.social.dto.SocialGovDTOs;
import com.hengde.social.entity.SocialPost;
import com.hengde.social.service.SocialAdminService;
import com.hengde.social.service.SocialBanService;
import com.hengde.social.service.SocialCommentService;
import com.hengde.social.service.SocialInteractionFeedService;
import com.hengde.social.service.SocialInteractionService;
import com.hengde.social.service.SocialKeywordService;
import com.hengde.social.service.SocialPostService;
import com.hengde.social.service.SocialReportService;
import com.hengde.social.service.SocialReviewService;
import com.hengde.social.service.SocialUserService;
import com.hengde.social.vo.SocialGovVOs;
import com.hengde.social.vo.SocialVOs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 社区治理（V4 社区治理批，V72 / V73）：关键词先藏后审、多级审核、驳回、调级数、隐藏置顶、真实姓名、举报、禁言、互动与汇总提示。
 *
 * <p>审核级数是全库一行配置：会改它的用例在结束前改回 1。<b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class SocialGovernanceTest extends SocialTestSupport {

    @Autowired
    private SocialPostService postService;
    @Autowired
    private SocialInteractionService interactionService;
    @Autowired
    private SocialCommentService commentService;
    @Autowired
    private SocialUserService userService;
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
    private SanctionQueryService sanctionQueryService;
    @Autowired
    private SocialPostMapper postMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void keywordHit_hiddenUntilApproved_andJumpsTheQueue() {
        String word = "违禁词" + SEQ.incrementAndGet();
        keywordService.add(admin("宣传部"), word);
        assertMessage("已经有这个关键词", () -> keywordService.add(admin("宣传部"), word));
        Long author = member();
        Long reader = member();
        Long plain = postService.publish(author, text("普通帖"));
        Long hit = postService.publish(author, text("里面有" + word.toUpperCase() + "的帖子"));

        SocialPost p = postMapper.selectById(hit);
        assertEquals(1, p.getKeywordHit());
        assertEquals(word, p.getKeywordHits());
        assertMessage("无权查看", () -> postService.detail(reader, hit));
        assertEquals(plain, postService.detail(reader, plain).getId(), "先发后审：没命中关键词的照常显示");
        SocialVOs.Post mine = postService.detail(author, hit);
        assertTrue(mine.getAwaitingKeywordReview());
        assertEquals("审核中", mine.getReviewStatusLabel());

        Long superAdmin = admin("理事会");
        List<Long> queue = reviewService.queue(superAdmin, true, false, page(500)).getRecords().stream()
                .map(SocialGovVOs.AdminPost::getId).toList();
        assertTrue(queue.indexOf(hit) >= 0 && queue.indexOf(hit) < queue.indexOf(plain), "命中关键词的插队在前：" + queue);

        reviewService.approve(superAdmin, true, hit);
        assertEquals(hit, postService.detail(reader, hit).getId(), "审过之后放出来");
        assertEquals("已通过", postService.detail(author, hit).getReviewStatusLabel());

        // 改帖重新判关键词、重回待审
        postService.update(author, hit, text("改成还是有 " + word));
        assertMessage("无权查看", () -> postService.detail(reader, hit));
        assertEquals(0, postMapper.selectById(hit).getReviewLevel());
    }

    @Test
    void multiLevelReview_distinctPeoplePerLevel_rejectNotifies_loweringCompletes() {
        Long lv1 = admin("组织部");
        Long lv2 = admin("监察部");
        Long both = admin("秘书部");
        reviewService.addReviewer(lv1, lv1, 1);
        reviewService.addReviewer(lv1, lv2, 2);
        reviewService.addReviewer(lv1, both, 1);
        reviewService.addReviewer(lv1, both, 2);
        assertMessage("已经是第 1 级", () -> reviewService.addReviewer(lv1, lv1, 1));
        reviewService.setLevels(lv1, 2);
        try {
            Long author = member();
            Long post = postService.publish(author, text("两级审核"));
            assertTrue(ids(reviewService.queue(lv1, false, false, page(500))).contains(post));
            assertFalse(ids(reviewService.queue(lv2, false, false, page(500))).contains(post), "第 2 级还轮不到");
            assertMessage("不是这一级", () -> reviewService.approve(lv2, false, post));

            reviewService.approve(both, false, post);
            assertEquals(1, postMapper.selectById(post).getReviewLevel());
            assertEquals(0, postMapper.selectById(post).getReviewStatus(), "一级还不算通过");
            assertMessage("换一个人", () -> reviewService.approve(both, false, post));
            assertTrue(ids(reviewService.queue(lv2, false, false, page(500))).contains(post));
            reviewService.approve(lv2, false, post);
            assertEquals(SocialGovCodes.REVIEW_APPROVED, postMapper.selectById(post).getReviewStatus());
            assertMessage("已经审核完了", () -> reviewService.approve(lv1, false, post));

            Long bad = postService.publish(author, text("要被驳回"));
            Long reader = member();
            reviewService.reject(lv1, false, bad, "内容不当");
            assertMessage("无权查看", () -> postService.detail(reader, bad));
            assertEquals("未通过", postService.detail(author, bad).getReviewStatusLabel());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM volunteer_notification WHERE volunteer_id = ? AND type = ? AND biz_id = ?",
                    Integer.class, author, VolunteerNotification.TYPE_SOCIAL_POST_REJECTED, bad));

            Long waiting = postService.publish(author, text("调低级数"));
            reviewService.approve(lv1, false, waiting);
            reviewService.setLevels(lv1, 1);
            assertEquals(SocialGovCodes.REVIEW_APPROVED, postMapper.selectById(waiting).getReviewStatus(), "调低之后够级数的直接算通过");
            assertMessage("1~3", () -> reviewService.setLevels(lv1, 4));
        } finally {
            reviewService.setLevels(lv1, 1);
        }
    }

    @Test
    void adminHidePinDelete_andRealNameOnlyWhenAllowed() {
        Long author = member();
        Long reader = member();
        Long post = postService.publish(author, text("后台管理"));
        adminService.setHidden(post, true);
        assertMessage("无权查看", () -> postService.detail(reader, post));
        assertTrue(postService.detail(author, post).getHiddenByAdmin());
        adminService.setHidden(post, false);
        assertEquals(post, postService.detail(reader, post).getId());

        Long older = postService.publish(member(), text("先发的"));
        adminService.setPinned(older, true);
        Long newer = postService.publish(member(), text("后发的"));
        List<Long> latest = postService.feed(reader, "latest", null, page(5)).getRecords().stream().map(SocialVOs.Post::getId).toList();
        assertEquals(older, latest.get(0), "置顶在最前：" + latest);
        assertTrue(latest.contains(newer));
        adminService.setPinned(older, false);

        SocialGovVOs.AdminPost hiddenName = adminService.posts(null, null, null, author, null, false, page(10)).getRecords().get(0);
        assertNull(hiddenName.getRealName());
        SocialGovVOs.AdminPost withName = adminService.posts(null, null, null, author, null, true, page(10)).getRecords().get(0);
        assertTrue(withName.getRealName().startsWith("真实姓名"));

        Long comment = commentService.comment(reader, post, commentOf("后台要删的评论", null));
        assertEquals(1, adminService.comments(post, null, null, false, page(10)).getRecords().size());
        adminService.deleteComment(admin("宣传部"), comment);
        assertEquals(0, postMapper.selectById(post).getCommentCount());
        adminService.deletePost(admin("宣传部"), post);
        assertNull(postMapper.selectById(post));
    }

    @Test
    void reports_dedupe_resolveByTarget_andActions() {
        Long author = member();
        Long r1 = member();
        Long r2 = member();
        Long post = postService.publish(author, text("被举报的帖子"));
        SocialGovDTOs.ReportSave rp = report(SocialGovCodes.REPORT_TARGET_POST, post, "广告");
        Long first = reportService.report(r1, rp);
        assertMessage("已经举报过", () -> reportService.report(r1, rp));
        assertMessage("不能举报自己", () -> reportService.report(author, rp));
        reportService.report(r2, rp);

        SocialGovDTOs.ReportHandle hide = new SocialGovDTOs.ReportHandle();
        hide.setAction(SocialGovCodes.ACTION_HIDE);
        hide.setNote("隐藏处理");
        Long handler = admin("监察部");
        reportService.uphold(handler, first, hide);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM social_report WHERE target_type = 1 AND target_id = ? AND status = 0",
                Integer.class, post), "同一对象的举报一起结案");
        assertEquals(1, postMapper.selectById(post).getAdminHidden());
        assertMessage("已经处理过", () -> reportService.uphold(handler, first, hide));
        assertMessage("无权查看", () -> reportService.report(member(), rp));

        Long open = postService.publish(author, text("评论会被举报"));
        Long c = commentService.comment(r1, open, commentOf("骂人的评论", null));
        SocialGovDTOs.ReportSave rc = report(SocialGovCodes.REPORT_TARGET_COMMENT, c, "辱骂");
        Long cr = reportService.report(r2, rc);
        assertMessage("不能隐藏", () -> reportService.uphold(handler, cr, hide));
        SocialGovDTOs.ReportHandle del = new SocialGovDTOs.ReportHandle();
        del.setAction(SocialGovCodes.ACTION_DELETE);
        reportService.uphold(handler, cr, del);
        assertEquals(0, postMapper.selectById(open).getCommentCount());

        Long p2 = postService.publish(author, text("不成立的举报"));
        Long d = reportService.report(r1, report(SocialGovCodes.REPORT_TARGET_POST, p2, "看着不顺眼"));
        reportService.dismiss(handler, d, new SocialGovDTOs.ReportHandle());
        assertNotNull(reportService.report(r1, report(SocialGovCodes.REPORT_TARGET_POST, p2, "再举报一次")), "结案之后可以再举报");
        assertEquals(0, postMapper.selectById(p2).getAdminHidden());
    }

    @Test
    void bans_scopedDaysAndLift() {
        Long me = member();
        Long post = postService.publish(member(), text("禁言测试"));
        Long admin = admin("宣传部");
        assertMessage("只能限制社区", () -> banService.ban(admin, ban(me, SanctionScope.ALL, 3)));
        assertMessage("只能限制社区", () -> banService.ban(admin, ban(me, SanctionScope.ACTIVITY, 3)));
        assertMessage("天数", () -> banService.ban(admin, ban(me, SanctionScope.COMMUNITY_COMMENT, 0)));

        Long banId = banService.ban(admin, ban(me, SanctionScope.COMMUNITY_COMMENT, 3));
        assertMessage("禁止评论", () -> commentService.comment(me, post, commentOf("禁言中", null)));
        assertNotNull(postService.publish(me, text("禁评不禁发")));
        assertTrue(sanctionQueryService.activeSanctions(me).stream().anyMatch(s -> s.getScope() == SanctionScope.COMMUNITY_COMMENT));
        SocialGovVOs.Ban vo = banService.list(me, page(10)).getRecords().get(0);
        assertTrue(vo.isActive());
        assertEquals("禁止评论", vo.getScopeLabel());

        banService.lift(admin, banId, "误伤");
        assertNotNull(commentService.comment(me, post, commentOf("解除了", null)));
        assertMessage("已经解除", () -> banService.lift(admin, banId, "再解除"));
        assertFalse(banService.list(me, page(10)).getRecords().get(0).isActive());
    }

    @Test
    void interactions_recordedDedupedListedAndDigestedOnce() {
        Long author = member();
        Long fan = member();
        Long third = member();
        Long post = postService.publish(author, text("互动帖子"));
        interactionService.like(fan, post);
        interactionService.unlike(fan, post);
        interactionService.like(fan, post);
        interactionService.like(author, post);   // 自己赞自己不记
        Long thirdComment = commentService.comment(third, post, commentOf("第三人的评论", null));
        commentService.comment(fan, post, commentOf("回复第三人", thirdComment));
        userService.follow(fan, author);
        userService.unfollow(fan, author);
        userService.follow(fan, author);

        List<SocialGovVOs.Interaction> mine = feedService.list(author, page(20)).getRecords();
        assertEquals(List.of(SocialGovCodes.INTERACT_FOLLOW, SocialGovCodes.INTERACT_COMMENT, SocialGovCodes.INTERACT_COMMENT,
                SocialGovCodes.INTERACT_LIKE), mine.stream().map(SocialGovVOs.Interaction::getType).toList(), "反复点赞 / 关注只记一条");
        assertEquals("互动帖子", mine.get(3).getPostSnippet());
        assertEquals("赞了你的帖子", mine.get(3).getTypeLabel());
        assertEquals(List.of(SocialGovCodes.INTERACT_REPLY), feedService.list(third, page(20)).getRecords().stream()
                .map(SocialGovVOs.Interaction::getType).toList(), "被回复的人收到「回复了你的评论」");

        assertEquals(4, feedService.unreadCount(author));
        feedService.digestOnce();
        feedService.digestOnce();
        assertEquals(1, digests(author), "同一段互动只提示一次");
        assertEquals(1, digests(third));
        interactionService.like(third, post);
        feedService.digestOnce();
        assertEquals(2, digests(author), "有新互动再提示");

        feedService.markAllRead(author);
        assertEquals(0, feedService.unreadCount(author));
        Long someId = mine.get(0).getId();
        feedService.delete(author, someId);
        assertMessage("互动不存在", () -> feedService.delete(fan, mine.get(1).getId()));
        Set<Long> left = feedService.list(author, page(20)).getRecords().stream().map(SocialGovVOs.Interaction::getId).collect(Collectors.toSet());
        assertFalse(left.contains(someId));
    }

    // ------------------------------------------------------------------

    private long digests(Long volunteerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM volunteer_notification WHERE volunteer_id = ? AND type = ?",
                Long.class, volunteerId, VolunteerNotification.TYPE_SOCIAL_INTERACTIONS);
    }

    private static Set<Long> ids(com.hengde.common.page.PageResult<SocialGovVOs.AdminPost> page) {
        return page.getRecords().stream().map(SocialGovVOs.AdminPost::getId).collect(Collectors.toSet());
    }

    static SocialGovDTOs.ReportSave report(int type, Long id, String reason) {
        SocialGovDTOs.ReportSave r = new SocialGovDTOs.ReportSave();
        r.setTargetType(type);
        r.setTargetId(id);
        r.setReason(reason);
        return r;
    }

    static SocialGovDTOs.BanSave ban(Long volunteerId, int scope, int days) {
        SocialGovDTOs.BanSave b = new SocialGovDTOs.BanSave();
        b.setVolunteerId(volunteerId);
        b.setScope(scope);
        b.setDays(days);
        b.setReason("测试禁言");
        return b;
    }
}
