package com.hengde.social;

import com.hengde.auth.constant.SanctionScope;
import com.hengde.auth.entity.VolunteerSanction;
import com.hengde.auth.service.SanctionQueryService;
import com.hengde.auth.service.SanctionService;
import com.hengde.common.oss.PresignedUpload;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.social.constant.SocialCodes;
import com.hengde.social.dao.SocialPostMapper;
import com.hengde.social.dto.SocialDTOs;
import com.hengde.social.entity.SocialPost;
import com.hengde.social.service.SocialCommentService;
import com.hengde.social.service.SocialInteractionService;
import com.hengde.social.service.SocialMediaService;
import com.hengde.social.service.SocialOfficialPostService;
import com.hengde.social.service.SocialPostService;
import com.hengde.social.service.SocialUserService;
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
 * 社区核心（V4 社区核心批，V71）：四层门、发帖校验、可见性矩阵、帖子流、点赞 / 评论 / 关注 / 主页 / 设置、官方帖、视频直传签名。
 *
 * <p>帖子流是全库的，断言只看本用例建的帖子（按 id 取交集）。<b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class SocialCoreTest extends SocialTestSupport {

    @Autowired
    private SocialPostService postService;
    @Autowired
    private SocialInteractionService interactionService;
    @Autowired
    private SocialCommentService commentService;
    @Autowired
    private SocialUserService userService;
    @Autowired
    private SocialOfficialPostService officialPostService;
    @Autowired
    private SocialMediaService mediaService;
    @Autowired
    private SocialPostMapper postMapper;
    @Autowired
    private SanctionService sanctionService;
    @Autowired
    private SanctionQueryService sanctionQueryService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void gates_phoneToView_registrationToAct() {
        Long author = member();
        Long post = postService.publish(author, text("大家好"));
        Long noPhone = volunteer(true, false, "没验手机");
        Long guest = volunteer(false, true, "游客");

        assertMessage("验证手机号", () -> postService.feed(noPhone, null, null, page(10)));
        assertMessage("验证手机号", () -> postService.detail(noPhone, post));
        assertMessage("验证手机号", () -> userService.profile(noPhone, author));

        assertEquals(post, postService.detail(guest, post).getId(), "游客验过手机号就能看");
        assertMessage("实名注册后", () -> postService.publish(guest, text("游客发帖")));
        assertMessage("实名注册后", () -> interactionService.like(guest, post));
        assertMessage("实名注册后", () -> commentService.comment(guest, post, commentOf("游客评论", null)));
        assertMessage("实名注册后", () -> userService.follow(guest, author));
    }

    @Test
    void publish_validatesMedia() {
        Long me = member();
        assertMessage("写点什么", () -> postService.publish(me, text("  ")));
        SocialDTOs.PostSave tooMany = text("图多");
        tooMany.setImageUrls(images(10));
        assertMessage("最多 9 张", () -> postService.publish(me, tooMany));
        SocialDTOs.PostSave foreign = text("外链");
        foreign.setImageUrls(List.of("https://evil.example.com/a.jpg"));
        assertMessage("先通过小程序上传", () -> postService.publish(me, foreign));
        SocialDTOs.PostSave wrongDir = text("别的目录");
        wrongDir.setImageUrls(List.of(ownUrl("avatar", "jpg")));
        assertMessage("先通过小程序上传", () -> postService.publish(me, wrongDir));
        SocialDTOs.PostSave both = text("图和视频");
        both.setImageUrls(images(1));
        both.setVideoUrl(ownUrl("social-video", "mp4"));
        assertMessage("二选一", () -> postService.publish(me, both));
        SocialDTOs.PostSave badVisibility = withVisibility("可见性", 9);
        assertMessage("可见性", () -> postService.publish(me, badVisibility));
        SocialDTOs.PostSave longText = text("字".repeat(2001));
        assertMessage("2000", () -> postService.publish(me, longText));

        SocialDTOs.PostSave nine = text("九张图");
        nine.setImageUrls(images(9));
        Long id = postService.publish(me, nine);
        SocialVOs.Post vo = postService.detail(me, id);
        assertEquals(SocialCodes.MEDIA_IMAGE, vo.getMediaType());
        assertEquals(9, vo.getMediaUrls().size());
        assertEquals(nine.getImageUrls(), vo.getMediaUrls(), "图片顺序原样保留");
        assertEquals(SocialCodes.REVIEW_PENDING, postMapper.selectById(id).getReviewStatus(), "先发后审");
    }

    @Test
    void visibilityMatrix_followsAndBlocks() {
        Long a = member();
        Long b = member();
        Long open = postService.publish(a, withVisibility("公开", SocialCodes.VISIBLE_ALL));
        Long hidden = postService.publish(a, withVisibility("隐藏", SocialCodes.VISIBLE_SELF));
        Long myFollowing = postService.publish(a, withVisibility("我关注的人可看", SocialCodes.VISIBLE_MY_FOLLOWING));
        Long myFollowers = postService.publish(a, withVisibility("关注我的人可看", SocialCodes.VISIBLE_MY_FOLLOWERS));
        Set<Long> all = Set.of(open, hidden, myFollowing, myFollowers);

        assertEquals(all, visibleOf(a, a), "自己全看得到");
        assertEquals(Set.of(open), visibleOf(b, a), "陌生人只看得到公开的");
        assertMessage("无权查看", () -> postService.detail(b, hidden));

        userService.follow(a, b);   // a 关注了 b → b 在 a 的「我关注的人」里
        assertEquals(Set.of(open, myFollowing), visibleOf(b, a));
        userService.follow(b, a);   // b 关注了 a → b 也是 a 的粉丝
        assertEquals(Set.of(open, myFollowing, myFollowers), visibleOf(b, a));
        assertEquals(3, userService.profile(b, a).getPostCount(), "发帖量只数看得到的");
        assertEquals(4, userService.profile(a, a).getPostCount());

        userService.block(a, b);    // 不让 b 看
        assertEquals(Set.of(), visibleOf(b, a), "不让TA看：一条都看不到");
        assertMessage("无权查看", () -> interactionService.like(b, open));
        assertMessage("无权查看", () -> commentService.comment(b, open, commentOf("看不到也评不了", null)));
        userService.unblock(a, b);
        assertEquals(3, visibleOf(b, a).size());

        // 帖子流里同样生效
        Long c = member();
        Set<Long> inFeed = postService.feed(c, "latest", null, page(200)).getRecords().stream()
                .map(SocialVOs.Post::getId).collect(Collectors.toSet());
        assertTrue(inFeed.contains(open));
        assertFalse(inFeed.contains(hidden) || inFeed.contains(myFollowing) || inFeed.contains(myFollowers), "帖子流不漏受限帖子");
    }

    @Test
    void feedTabs_followingOfficialAndKeyword() {
        Long reader = member();
        Long followed = member();
        Long other = member();
        String tag = "关键词" + SEQ.incrementAndGet();
        Long fp = postService.publish(followed, text("关注的人发的 " + tag));
        Long op = postService.publish(other, text("路人发的"));
        userService.follow(reader, followed);
        Long official = officialPostService.publish(admin("宣传部"), officialPost("官方公告 " + tag, "暑期支教"));

        Set<Long> following = ids(postService.feed(reader, "following", null, page(200)).getRecords());
        assertTrue(following.contains(fp));
        assertFalse(following.contains(op) || following.contains(official));

        Set<Long> officials = ids(postService.feed(reader, "official", null, page(200)).getRecords());
        assertTrue(officials.contains(official));
        assertFalse(officials.contains(fp));
        SocialVOs.Post ov = postService.detail(reader, official);
        assertEquals("官方 · 宣传部", ov.getAuthor().getName());
        assertEquals("暑期支教", ov.getOfficialLabel());

        assertEquals(Set.of(fp, official), ids(postService.feed(reader, "latest", tag, page(50)).getRecords()));
        Long pct = postService.publish(other, text("打折 100% 真的"));
        Set<Long> percent = ids(postService.feed(reader, "latest", "%", page(500)).getRecords());
        assertTrue(percent.contains(pct));
        assertFalse(percent.contains(op), "% 按字面匹配，不是通配");
        assertMessage("页签", () -> postService.feed(reader, "nope", null, page(10)));
    }

    @Test
    void editAndDelete_authorOnly() {
        Long a = member();
        Long b = member();
        Long id = postService.publish(a, text("原文"));
        jdbc.update("UPDATE social_post SET review_status = 1 WHERE id = ?", id);
        assertMessage("帖子不存在", () -> postService.update(b, id, text("别人改")));
        SocialDTOs.PostSave edit = withVisibility("改过", SocialCodes.VISIBLE_SELF);
        edit.setAllowComment(false);
        postService.update(a, id, edit);
        SocialPost p = postMapper.selectById(id);
        assertEquals("改过", p.getContent());
        assertEquals(0, p.getAllowComment());
        assertEquals(SocialCodes.REVIEW_PENDING, p.getReviewStatus(), "改完重回待审核");
        assertNotNull(p.getEditTime());

        assertMessage("帖子不存在", () -> postService.delete(b, id));
        postService.delete(a, id);
        assertNull(postMapper.selectById(id));
        assertMessage("无权查看", () -> postService.detail(a, id));
        assertMessage("帖子不存在", () -> postService.delete(a, id));
    }

    @Test
    void likes_idempotent_andSwitches() {
        Long a = member();
        Long b = member();
        Long id = postService.publish(a, text("点赞我"));
        assertTrue(interactionService.like(b, id));
        assertFalse(interactionService.like(b, id), "再点不报错也不多记");
        assertEquals(1, postMapper.selectById(id).getLikeCount());
        assertTrue(postService.detail(b, id).isLikedByMe());
        assertTrue(interactionService.unlike(b, id));
        assertFalse(interactionService.unlike(b, id));
        assertEquals(0, postMapper.selectById(id).getLikeCount());

        SocialDTOs.PostSave noLike = text("别点赞");
        noLike.setAllowLike(false);
        Long closed = postService.publish(a, noLike);
        assertMessage("不允许点赞", () -> interactionService.like(b, closed));

        SocialDTOs.SettingSave s = new SocialDTOs.SettingSave();
        s.setForbidLike(true);
        userService.saveSetting(a, s);
        assertMessage("不允许点赞", () -> interactionService.like(b, id));
        assertFalse(postService.detail(b, id).isLikeable());
        userService.saveSetting(a, new SocialDTOs.SettingSave());
        assertTrue(interactionService.like(b, id));
        assertEquals(1, userService.profile(b, a).getLikeCount());
    }

    @Test
    void comments_replyDeleteAndSwitches() {
        Long a = member();
        Long b = member();
        Long c = member();
        Long id = postService.publish(a, text("评论我"));
        Long first = commentService.comment(b, id, commentOf(" 第一条 ", null));
        Long reply = commentService.comment(a, id, commentOf("回复 b", first));
        Long third = commentService.comment(c, id, commentOf("c 来了", null));
        assertEquals(3, postMapper.selectById(id).getCommentCount());

        List<SocialVOs.Comment> list = commentService.list(b, id, page(10)).getRecords();
        assertEquals(List.of(first, reply, third), list.stream().map(SocialVOs.Comment::getId).toList());
        assertEquals("第一条", list.get(0).getContent());
        assertEquals(b, list.get(1).getReplyTo().getVolunteerId());
        assertTrue(list.get(0).isDeletable(), "自己的评论能删");
        assertFalse(list.get(2).isDeletable(), "别人的评论（帖子也不是自己的）不能删");

        assertMessage("评论不存在", () -> commentService.delete(c, first));
        commentService.delete(a, first);   // 帖子作者删别人的评论
        commentService.delete(c, third);   // 自己删自己的
        assertEquals(1, postMapper.selectById(id).getCommentCount());
        assertMessage("评论不存在", () -> commentService.delete(c, third));

        Long other = postService.publish(c, text("另一帖"));
        assertMessage("回复的评论不存在", () -> commentService.comment(b, other, commentOf("串帖回复", reply)));

        SocialDTOs.SettingSave s = new SocialDTOs.SettingSave();
        s.setForbidComment(true);
        userService.saveSetting(a, s);
        assertMessage("不允许评论", () -> commentService.comment(b, id, commentOf("被禁", null)));

        Set<Long> bComments = commentService.userComments(c, b, page(50)).getRecords().stream()
                .map(SocialVOs.Comment::getId).collect(Collectors.toSet());
        assertFalse(bComments.contains(first), "删掉的评论不在 TA 的评论里");
    }

    @Test
    void follows_profileAndLists() {
        Long a = member();
        Long b = member();
        Long c = member();
        assertMessage("不能关注自己", () -> userService.follow(a, a));
        assertTrue(userService.follow(b, a));
        assertFalse(userService.follow(b, a));
        assertTrue(userService.follow(c, a));
        userService.follow(a, b);
        SocialVOs.Profile pa = userService.profile(b, a);
        assertEquals(2, pa.getFollowerCount());
        assertEquals(1, pa.getFollowingCount());
        assertTrue(pa.isFollowedByMe());
        assertTrue(pa.isFollowsMe());
        assertTrue(pa.getName().startsWith("社员"), "露昵称：" + pa.getName());
        assertFalse(pa.getName().contains("真实姓名"));
        assertEquals(Set.of(b, c), userService.followers(c, a, page(10)).getRecords().stream()
                .map(SocialVOs.UserCard::getVolunteerId).collect(Collectors.toSet()));

        SocialDTOs.SettingSave s = new SocialDTOs.SettingSave();
        s.setForbidFollow(true);
        s.setBio("  热爱志愿  ");
        userService.saveSetting(a, s);
        Long d = member();
        assertMessage("禁止关注", () -> userService.follow(d, a));
        assertEquals("热爱志愿", userService.profile(d, a).getBio());
        assertTrue(userService.profile(d, a).isFollowForbidden());
        assertTrue(userService.unfollow(b, a));
        assertEquals(1, userService.profile(d, a).getFollowerCount());
        assertMessage("用户不存在", () -> userService.profile(d, Long.MAX_VALUE));
    }

    @Test
    void sanctions_fineGrainedScopes_andCommunityImpliesThem() {
        Long a = member();
        Long target = postService.publish(member(), text("被互动的帖子"));

        Long noPost = member();
        sanction(noPost, SanctionScope.COMMUNITY_POST);
        assertMessage("发帖", () -> postService.publish(noPost, text("禁言中")));
        assertTrue(interactionService.like(noPost, target), "只禁发帖不挡点赞");
        assertNotNull(commentService.comment(noPost, target, commentOf("只禁发帖不挡评论", null)));

        Long noLike = member();
        sanction(noLike, SanctionScope.COMMUNITY_LIKE);
        assertMessage("点赞", () -> interactionService.like(noLike, target));
        assertNotNull(postService.publish(noLike, text("禁点赞照样能发帖")));

        Long community = member();
        sanction(community, SanctionScope.COMMUNITY);
        assertMessage("发帖", () -> postService.publish(community, text("x")));
        assertMessage("评论", () -> commentService.comment(community, target, commentOf("x", null)));
        assertMessage("点赞", () -> interactionService.like(community, target));
        assertFalse(sanctionQueryService.isRestricted(community, SanctionScope.ACTIVITY), "限制社区不管活动");

        Long all = member();
        sanction(all, SanctionScope.ALL);
        assertMessage("评论", () -> commentService.comment(all, target, commentOf("x", null)));
        assertTrue(sanctionQueryService.isRestricted(all, SanctionScope.COMMUNITY_LIKE));
        assertEquals("禁止点赞", SanctionScope.labelOf(SanctionScope.COMMUNITY_LIKE));
        assertNotNull(postService.publish(a, text("没被处置的人照常发帖")));
    }

    @Test
    void officialPosts_departmentRules() {
        Long publicity = admin("宣传部");
        Long org = admin("组织部");
        Long noDept = admin("");
        assertMessage("没有填写部门", () -> officialPostService.publish(noDept, officialPost("x", null)));
        Long orgPost = officialPostService.publish(org, officialPost("组织部公告", "某某分队"));
        Long volunteerComment = commentService.comment(member(), orgPost, commentOf("志愿者评论官方帖", null));
        Long officialReply = commentService.officialComment(org, "组织部", orgPost, commentOf("官方回复", volunteerComment));
        assertEquals(2, postMapper.selectById(orgPost).getCommentCount());
        assertEquals("官方 · 组织部", commentService.officialList(orgPost, page(10)).getRecords().get(1).getAuthor().getName());

        assertMessage("不是本部门", () -> officialPostService.delete(publicity, "宣传部", orgPost));
        assertMessage("评论不存在", () -> commentService.officialDelete(publicity, "宣传部", volunteerComment));
        commentService.officialDelete(org, "组织部", volunteerComment);
        commentService.officialDelete(publicity, null, officialReply);   // 全部门权限
        assertEquals(0, postMapper.selectById(orgPost).getCommentCount());
        assertMessage("官方帖不存在", () -> commentService.officialComment(org, "组织部", postService.publish(member(), text("志愿者帖")),
                commentOf("官方不评志愿者帖", null)));

        assertTrue(officialPostService.list("组织部", page(100)).getRecords().stream().anyMatch(p -> p.getId().equals(orgPost)));
        officialPostService.delete(publicity, null, orgPost);
        assertNull(postMapper.selectById(orgPost));
    }

    @Test
    void videoPresign_thenPublishWithIt() {
        Long me = member();
        assertMessage("mp4", () -> mediaService.presignVideo("exe", 100));
        assertMessage("100MB", () -> mediaService.presignVideo("mp4", 101L * 1024 * 1024));
        assertMessage("多大", () -> mediaService.presignVideo("mp4", 0));
        PresignedUpload up = mediaService.presignVideo(".MP4", 5 * 1024 * 1024);
        assertEquals("PUT", up.method());
        assertEquals("video/mp4", up.headers().get("Content-Type"));
        assertTrue(up.url().startsWith("[oss-disabled]/social-video/"), up.url());
        SocialDTOs.PostSave video = text("一段视频");
        video.setVideoUrl(up.url());
        Long id = postService.publish(me, video);
        SocialVOs.Post vo = postService.detail(me, id);
        assertEquals(SocialCodes.MEDIA_VIDEO, vo.getMediaType());
        assertEquals(List.of(up.url()), vo.getMediaUrls());
    }

    // ------------------------------------------------------------------

    private Set<Long> visibleOf(Long viewer, Long author) {
        return ids(postService.userPosts(viewer, author, page(100)).getRecords());
    }

    private static Set<Long> ids(List<SocialVOs.Post> posts) {
        return posts.stream().map(SocialVOs.Post::getId).collect(Collectors.toSet());
    }

    private void sanction(Long volunteerId, int scope) {
        sanctionService.impose(volunteerId, VolunteerSanction.SOURCE_REWARD_PUNISH, SEQ.incrementAndGet() + 9_000_000L, scope, 3);
    }

    static SocialDTOs.OfficialPostSave officialPost(String content, String label) {
        SocialDTOs.OfficialPostSave d = new SocialDTOs.OfficialPostSave();
        d.setContent(content);
        d.setLabel(label);
        return d;
    }
}
