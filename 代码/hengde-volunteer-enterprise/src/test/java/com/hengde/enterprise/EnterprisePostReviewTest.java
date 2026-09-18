package com.hengde.enterprise;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.entity.MallOrder;
import com.hengde.donate.service.MallGoodsService;
import com.hengde.donate.service.MallOrderService;
import com.hengde.enterprise.dto.EnterpriseReviewDTOs;
import com.hengde.enterprise.service.EnterpriseAdminService;
import com.hengde.enterprise.service.EnterpriseReviewService;
import com.hengde.enterprise.service.EnterpriseSponsorService;
import com.hengde.enterprise.vo.EnterpriseReviewVO;
import com.hengde.social.dto.SocialDTOs;
import com.hengde.social.service.SocialCommentService;
import com.hengde.social.service.SocialInteractionService;
import com.hengde.social.service.SocialKeywordService;
import com.hengde.social.service.SocialPostService;
import com.hengde.social.service.SocialAdminService;
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
import java.util.Map;

import static com.hengde.enterprise.EnterpriseTestSupport.ADMIN;
import static com.hengde.enterprise.EnterpriseTestSupport.assertMessage;
import static com.hengde.enterprise.SponsorTestSupport.goods;
import static com.hengde.enterprise.SponsorTestSupport.normalEnterprise;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 爱心企业批·社区段：企业发帖（与志愿者帖同一张表、同一套先发后审与关键词风控、作者名头像取快照、只能管自己的、驳回不写站内提示）
 * 与赞助商评价（凭自己已领取的该企业赞助兑换单、一单一评、屏蔽 / 恢复 / 删除）。
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class EnterprisePostReviewTest {

    @Autowired
    private EnterpriseAdminService adminService;
    @Autowired
    private EnterpriseSponsorService sponsorService;
    @Autowired
    private EnterpriseReviewService reviewService;
    @Autowired
    private SocialPostService postService;
    @Autowired
    private SocialCommentService commentService;
    @Autowired
    private SocialInteractionService interactionService;
    @Autowired
    private SocialKeywordService keywordService;
    @Autowired
    private SocialReviewService reviewFlow;
    @Autowired
    private SocialUserService userService;
    @Autowired
    private SocialAdminService socialAdminService;
    @Autowired
    private MallGoodsService goodsService;
    @Autowired
    private MallOrderService orderService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void enterprisePost_showsSnapshotAuthor_ownOnly_keywordAndReject() {
        Long ent = normalEnterprise(adminService, "发帖企业");
        String name = jdbc.queryForObject("SELECT name FROM enterprise_account WHERE id = ?", String.class, ent);
        Long other = normalEnterprise(adminService, "别家企业");
        Long viewer = volunteer();

        Long postId = postService.publishForEnterprise(ent, name, "https://cdn.example.com/logo.png", post("我们又赞助了一批保温杯"));
        SocialVOs.Post seen = postService.detail(viewer, postId);
        assertEquals(3, seen.getAuthor().getType(), "作者类型是企业");
        assertEquals(name, seen.getAuthor().getName(), "作者名取发帖时的快照");
        assertEquals("https://cdn.example.com/logo.png", seen.getAuthor().getAvatarUrl());
        assertFalse(seen.isMine(), "志愿者看别人的帖子不是「我的」");
        assertTrue(ids(postService.enterprisePosts(viewer, ent, new PageQuery()).getRecords()).contains(postId), "企业主页有它");
        assertFalse(ids(postService.enterprisePosts(viewer, other, new PageQuery()).getRecords()).contains(postId));
        assertTrue(ids(postService.feed(viewer, "latest", null, new PageQuery()).getRecords()).contains(postId), "帖子流里也有");

        // 志愿者照常点赞评论；互动记录只写给志愿者作者，企业帖不该往 id 相同的志愿者那里塞
        assertTrue(interactionService.like(viewer, postId));
        commentService.comment(viewer, postId, comment("支持！"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM social_interaction WHERE recipient_id = ? AND post_id = ?",
                Integer.class, ent, postId), "企业不是志愿者，不给它写互动记录");

        assertMessage("帖子不存在", () -> postService.updateForEnterprise(other, postId, post("别家来改")));
        assertMessage("帖子不存在", () -> postService.deleteForEnterprise(other, postId));
        // ⚠️ 改帖前必须先让它审核通过：不通过的话它本来就停在「待审核」，下面那条断言什么也证明不了
        // （去掉「改完重置审核状态」那两行时这条用例照样绿，是变异验证当场抓出来的）
        reviewFlow.approve(ADMIN, true, postId);
        assertEquals(1, jdbc.queryForObject("SELECT review_status FROM social_post WHERE id = ?", Integer.class, postId));
        postService.updateForEnterprise(ent, postId, post("改了一下文案"));
        assertEquals("改了一下文案", postService.detail(viewer, postId).getContent());
        assertEquals(0, jdbc.queryForObject("SELECT review_status FROM social_post WHERE id = ?", Integer.class, postId), "改完重回待审核");
        assertEquals(0, jdbc.queryForObject("SELECT review_level FROM social_post WHERE id = ?", Integer.class, postId), "并且从第 0 级重新审");

        // 关键词命中：先藏后审，自己看得到、别人看不到
        String word = "违禁词" + EnterpriseTestSupport.next();
        keywordService.add(ADMIN, word);
        Long hit = postService.publishForEnterprise(ent, name, null, post("含有" + word + "的内容"));
        assertFalse(ids(postService.feed(viewer, "latest", null, new PageQuery()).getRecords()).contains(hit), "命中关键词的先藏起来");
        assertTrue(ids(postService.ownPostsOfEnterprise(ent, new PageQuery()).getRecords()).contains(hit), "企业自己看得到");
        assertTrue(postService.ownPostsOfEnterprise(ent, new PageQuery()).getRecords().get(0).isMine());

        // 驳回：对外隐藏，且不给「id 恰好相同的那个志愿者」写站内提示
        reviewFlow.reject(ADMIN, true, hit, "内容不合适");
        assertFalse(ids(postService.feed(viewer, "latest", null, new PageQuery()).getRecords()).contains(hit));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM volunteer_notification WHERE biz_id = ?", Integer.class, hit),
                "企业帖被驳回不写站内提示（企业没有收件箱）");

        postService.deleteForEnterprise(ent, postId);
        assertMessage("帖子不存在", () -> postService.detail(viewer, postId));
    }

    @Test
    void sponsorReview_needsOwnPickedSponsoredOrder_oneEach_hideShowDelete() {
        Long ent = normalEnterprise(adminService, "被评价企业");
        Long other = normalEnterprise(adminService, "不相干企业");
        Long goodsId = approvedGoods(ent);
        Long buyer = volunteer();
        SponsorTestSupport.givePoints(jdbc, buyer, 100);
        MallOrder order = orderService.placeOrder(buyer, SponsorTestSupport.specOf(jdbc, goodsId));

        assertMessage("只能评价自己已领取的", () -> reviewService.create(buyer, ent, create(order.getId(), 5, "还没领就评")));
        orderService.approve(order.getId(), ADMIN);
        orderService.verify(jdbc.queryForObject("SELECT pickup_code FROM mall_order WHERE id = ?", String.class, order.getId()), ADMIN);

        Long stranger = volunteer();
        assertMessage("只能评价自己已领取的", () -> reviewService.create(stranger, ent, create(order.getId(), 5, "别人的单")));
        assertMessage("只能评价自己已领取的", () -> reviewService.create(buyer, other, create(order.getId(), 5, "评到别家头上")));
        assertMessage("评分是 1~5", () -> reviewService.create(buyer, ent, create(order.getId(), 6, "超范围")));

        Long reviewId = reviewService.create(buyer, ent, create(order.getId(), 5, "东西不错，态度也好"));
        assertMessage("已经评价过了", () -> reviewService.create(buyer, ent, create(order.getId(), 4, "再评一次")));
        List<EnterpriseReviewVO> pub = reviewService.listPublic(ent, new PageQuery()).getRecords();
        assertEquals(1, pub.size());
        assertEquals("王**", pub.get(0).getVolunteerMaskedName(), "评价人只留姓");
        assertNull(pub.get(0).getStatus(), "公开列表不带屏蔽状态");
        assertEquals(1, reviewService.listMine(buyer, new PageQuery()).getRecords().size());

        reviewService.hide(reviewId, "含有不实信息", ADMIN);
        assertTrue(reviewService.listPublic(ent, new PageQuery()).getRecords().isEmpty(), "屏蔽后对外不可见");
        List<EnterpriseReviewVO> own = reviewService.listForEnterprise(ent, new PageQuery()).getRecords();
        assertEquals(1, own.size());
        assertEquals(1, own.get(0).getStatus());
        assertEquals("含有不实信息", own.get(0).getHiddenReason());
        assertMessage("已被屏蔽", () -> reviewService.hide(reviewId, "再屏蔽", ADMIN));
        reviewService.show(reviewId, ADMIN);
        assertEquals(1, reviewService.listPublic(ent, new PageQuery()).getRecords().size());
        assertMessage("未被屏蔽", () -> reviewService.show(reviewId, ADMIN));

        reviewService.delete(reviewId, ADMIN);
        assertTrue(reviewService.listPublic(ent, new PageQuery()).getRecords().isEmpty());
        assertTrue(reviewService.create(buyer, ent, create(order.getId(), 4, "删掉之后可以重评")) > 0, "删除释放了一单一评的键");
        assertEquals(1, reviewService.listForAdmin(ent, null, new PageQuery()).getRecords().size());
    }

    /**
     * 企业帖的 {@code author_id} 是<b>企业</b> id，与志愿者 id 是两套自增序列，迟早会撞上同一个数。
     *
     * <p>这条用例把「撞上」做成确定性的前提（显式插一个 id 恰好等于企业 id 的志愿者），钉住三处不许借用他的东西：
     * 他的「一律禁止评论」不能关掉企业帖的评论、他看企业帖不能被标成「我的」（连带露出审核状态）、
     * 后台开了真实姓名权限时企业帖不能显示成他的姓名与学校。</p>
     */
    @Test
    void enterprisePost_neverBorrowsFromTheVolunteerWithTheSameId() {
        // 两边都用同一个显式 id 造（企业与志愿者各自的自增序列都到不了这个号），撞上就是确定的，不看运气
        long sameId = 900_000_000L + EnterpriseTestSupport.next();
        String name = "同号企业" + sameId;
        Long ent = twinEnterprise(sameId, name);
        Long twin = twinVolunteer(sameId);
        Long onlooker = volunteer();

        SocialDTOs.SettingSave setting = new SocialDTOs.SettingSave();
        setting.setForbidComment(true);
        setting.setForbidLike(true);
        userService.saveSetting(twin, setting);

        String tag = "同号" + EnterpriseTestSupport.next();
        Long postId = postService.publishForEnterprise(ent, name, null, post("企业发的 " + tag));
        // ⚠️ 同号志愿者<b>自己也要有一条帖子</b>，而且要和企业帖出现在同一批里：发帖人设置与真实姓名都是按「这一批里的志愿者作者」
        // 批量取的，只有企业帖在场时那两张表本来就是空的，借用不到东西，用例也就证明不了什么（变异验证当场抓出来的）
        Long twinPost = postService.publish(twin, post("同号志愿者发的 " + tag));

        Map<Long, SocialVOs.Post> feed = postService.feed(onlooker, "latest", tag, new PageQuery()).getRecords().stream()
                .collect(java.util.stream.Collectors.toMap(SocialVOs.Post::getId, v -> v));
        assertTrue(feed.containsKey(postId) && feed.containsKey(twinPost), "两条帖子要在同一批里：" + feed.keySet());
        assertFalse(feed.get(twinPost).isCommentable(), "同号志愿者自己的帖子照他的设置禁止评论");
        assertTrue(feed.get(postId).isCommentable(), "企业帖的评论开关不该听同号志愿者的设置");
        assertTrue(feed.get(postId).isLikeable(), "点赞开关同理");
        assertEquals(name, feed.get(postId).getAuthor().getName());

        SocialVOs.Post byTwin = postService.detail(twin, postId);
        assertFalse(byTwin.isMine(), "id 恰好相同不等于这帖是他发的");
        assertNull(byTwin.getReviewStatusLabel(), "不是自己的帖子就不该看到审核状态");

        Map<Long, SocialGovVOs.AdminPost> adminRows = socialAdminService.posts(null, null, null, null, tag, true, new PageQuery())
                .getRecords().stream().collect(java.util.stream.Collectors.toMap(SocialGovVOs.AdminPost::getId, v -> v));
        assertEquals("同号志愿者", adminRows.get(twinPost).getRealName(), "志愿者帖照常给真实姓名（用例有效性自检）");
        assertNull(adminRows.get(postId).getRealName(), "企业帖不该显示同号志愿者的真实姓名");
        assertNull(adminRows.get(postId).getSchool());
        assertEquals(name, adminRows.get(postId).getAuthor().getName(), "后台看到的作者也是企业名");
    }

    // ================= 造数 =================

    private Long approvedGoods(Long ent) {
        Long id = sponsorService.createGoods(ent, goods("评价用商品", 10, 20));
        goodsService.submitForSponsor(id, ent);
        goodsService.approve(id, ADMIN);
        return id;
    }

    /** 造一个 id 指定的志愿者（用来和企业撞号；号取得足够大，不会和自增出来的行相撞）。 */
    private Long twinVolunteer(long id) {
        String phone = EnterpriseTestSupport.phone();
        jdbc.update("INSERT INTO volunteer (id, openid, real_name, school, phone, phone_hash, status, manager_flag, "
                        + "register_time, create_time, is_deleted) VALUES (?, ?, '同号志愿者', '同号中学', ?, ?, 0, 0, NOW(), NOW(), 0)",
                id, "test:twin:" + System.nanoTime() + ":" + EnterpriseTestSupport.next(),
                cryptoUtil.encrypt(phone), cryptoUtil.hashPhone(phone));
        return id;
    }

    /** 造一个 id 指定的正常企业。 */
    private Long twinEnterprise(long id, String name) {
        String phone = EnterpriseTestSupport.phone();
        jdbc.update("INSERT INTO enterprise_account (id, name, credit_code, leader_name, leader_phone, leader_phone_hash, "
                        + "username, password, status, source, submit_time, create_time, is_deleted) "
                        + "VALUES (?, ?, ?, '负责人', ?, ?, ?, 'x', 1, 2, NOW(), NOW(), 0)",
                id, name, ("T" + id + "000000000000000000").substring(0, 18),
                cryptoUtil.encrypt(phone), cryptoUtil.hashPhone(phone), "tw" + id);
        return id;
    }

    private Long volunteer() {
        return SponsorTestSupport.volunteer(volunteerMapper, cryptoUtil, EnterpriseTestSupport.phone());
    }

    private static SocialDTOs.PostSave post(String content) {
        SocialDTOs.PostSave d = new SocialDTOs.PostSave();
        d.setContent(content);
        return d;
    }

    private static SocialDTOs.CommentSave comment(String content) {
        SocialDTOs.CommentSave d = new SocialDTOs.CommentSave();
        d.setContent(content);
        return d;
    }

    private static EnterpriseReviewDTOs.Create create(Long orderId, int rating, String content) {
        EnterpriseReviewDTOs.Create d = new EnterpriseReviewDTOs.Create();
        d.setOrderId(orderId);
        d.setRating(rating);
        d.setContent(content);
        return d;
    }

    private static List<Long> ids(List<SocialVOs.Post> rows) {
        return rows.stream().map(SocialVOs.Post::getId).toList();
    }
}
