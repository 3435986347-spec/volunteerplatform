package com.hengde.api.stress;

import com.fasterxml.jackson.databind.JsonNode;
import com.hengde.api.HengdeVolunteerApplication;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 社区治理的 <b>HTTP 全流程</b>（V4 社区治理批）——<b>必跑</b>。
 *
 * <p>子账号真登录、真授权：审核员（social:review + 设为第 1 级）审关键词帖；管理员（social:post-manage，无 social:real-name）看不到真实姓名、超管看得到；
 * 举报处理人隐藏被举报的帖子、禁言一个人再解除；志愿者的互动列表与未读数；志愿者 token 调后台被拒。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(classes = HengdeVolunteerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hengde.auth.dev-login-enabled=true", "hengde.social.digest-enabled=false"})
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class SocialGovApiFlowTest {

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CryptoUtil cryptoUtil;

    @Test
    void reviewManageReportBanAndInteractionsOverHttp() {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String root = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);
        String word = "敏感" + tag;
        ok(c.post("/a/social/keywords", root, Map.of("word", word), "加关键词"));

        long reviewerId = subAccountId(c, root, "gr" + tag, "监察部", List.of(perm(c, root, "social:review")));
        String reviewer = c.adminLogin("gr" + tag, "pass1234");
        ok(c.post("/a/social/reviewers", root, Map.of("adminUserId", reviewerId, "level", 1), "设为第 1 级审核员"));
        subAccountId(c, root, "gm" + tag, "宣传部", List.of(perm(c, root, "social:post-manage")));
        String manager = c.adminLogin("gm" + tag, "pass1234");
        subAccountId(c, root, "gh" + tag, "监察部", List.of(perm(c, root, "social:report"), perm(c, root, "social:ban")));
        String handler = c.adminLogin("gh" + tag, "pass1234");

        String author = verified(c, "ga-" + tag);
        long authorId = idOf(c, author);
        String reader = verified(c, "gd-" + tag);
        long readerId = idOf(c, reader);

        // ---- 关键词先藏后审 → 审核员通过 ----
        long hit = ok(c.post("/v/social/posts", author, Map.of("content", "这里有" + word), "关键词帖")).dataAsLong();
        assertNotEquals(200, neg.get("/v/social/posts/" + hit, reader, "别人看关键词帖").code());
        boolean queued = false;
        for (JsonNode p : ok(c.get("/a/social/reviews?page=1&size=100", reviewer, "审核队列")).data().get("records")) {
            if (p.get("id").asLong() == hit) {
                queued = true;
                assertTrue(p.get("keywordHit").asBoolean());
            }
        }
        assertTrue(queued, "审核员看得到");
        ok(c.post("/a/social/reviews/" + hit + "/approve", reviewer, null, "通过"));
        ok(c.get("/v/social/posts/" + hit, reader, "通过后别人看得到"));
        assertNotEquals(200, neg.get("/a/social/reviews?page=1&size=5", reader, "志愿者 token 调审核").code());

        // ---- 真实姓名：管理员没有 social:real-name 看不到，超管看得到 ----
        JsonNode managed = ok(c.get("/a/social/posts?authorId=" + authorId + "&page=1&size=5", manager, "管理员看帖子")).data().get("records").get(0);
        assertFalse(managed.has("realName") && !managed.get("realName").isNull(), "没有 social:real-name 不下发真实姓名：" + managed);
        JsonNode rooted = ok(c.get("/a/social/posts?authorId=" + authorId + "&page=1&size=5", root, "超管看帖子")).data().get("records").get(0);
        assertTrue(rooted.path("realName").asText().startsWith("测试志愿者"), rooted.toString());

        // ---- 互动 ----
        ok(c.post("/v/social/posts/" + hit + "/like", reader, null, "点赞"));
        long comment = ok(c.post("/v/social/posts/" + hit + "/comments", reader, Map.of("content", "路过"), "评论")).dataAsLong();
        assertEquals(2, ok(c.get("/v/social/interactions/unread-count", author, "未读")).data().asInt());
        JsonNode interactions = ok(c.get("/v/social/interactions?page=1&size=10", author, "互动列表")).data().get("records");
        assertEquals("评论了你的帖子", interactions.get(0).get("typeLabel").asText());
        ok(c.post("/v/social/interactions/read", author, null, "标已读"));
        assertEquals(0, ok(c.get("/v/social/interactions/unread-count", author, "未读")).data().asInt());
        ok(c.delete("/a/social/comments/" + comment, manager, "管理员删任意评论"));

        // ---- 举报 → 隐藏 ----
        long report = ok(c.post("/v/social/reports", reader, Map.of("targetType", 1, "targetId", hit, "reason", "不合适"), "举报")).dataAsLong();
        assertNotEquals(200, neg.post("/v/social/reports", reader, Map.of("targetType", 1, "targetId", hit, "reason", "再举报"), "重复举报").code());
        ok(c.post("/a/social/reports/" + report + "/uphold", handler, Map.of("action", 1, "note", "隐藏"), "举报成立并隐藏"));
        assertNotEquals(200, neg.get("/v/social/posts/" + hit, reader, "隐藏后别人看不到").code());
        ok(c.get("/v/social/posts/" + hit, author, "作者自己还看得到"));

        // ---- 禁言 → 解除 ----
        long open = ok(c.post("/v/social/posts", author, Map.of("content", "普通帖 " + tag), "普通帖")).dataAsLong();
        long ban = ok(c.post("/a/social/bans", handler, Map.of("volunteerId", readerId, "scope", 5, "days", 1, "reason", "刷屏"), "禁止评论"))
                .dataAsLong();
        assertNotEquals(200, neg.post("/v/social/posts/" + open + "/comments", reader, Map.of("content", "禁言中"), "禁言中评论").code());
        boolean shown = false;
        for (JsonNode s : ok(c.get("/v/honor/sanctions", reader, "我的处置")).data()) {
            shown |= s.get("scope").asInt() == 5;
        }
        assertTrue(shown, "奖惩中心看得到禁言");
        assertNotEquals(200, neg.post("/a/social/bans", manager, Map.of("volunteerId", readerId, "scope", 5, "days", 1, "reason", "x"),
                "没有 social:ban").code());
        ok(c.post("/a/social/bans/" + ban + "/lift", handler, Map.of("reason", "误判"), "解除"));
        ok(c.post("/v/social/posts/" + open + "/comments", reader, Map.of("content", "解除了"), "解除后评论"));

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());
    }

    private String verified(ApiStressClient c, String key) {
        String token = c.devLogin(key);
        long id = idOf(c, token);
        String phone = "134" + String.format("%08d", Math.floorMod(System.nanoTime() + id, 100_000_000L));
        jdbc.update("UPDATE volunteer SET phone = ?, phone_hash = ? WHERE id = ?", cryptoUtil.encrypt(phone), cryptoUtil.hashPhone(phone), id);
        return token;
    }

    private static long idOf(ApiStressClient c, String token) {
        return ok(c.get("/v/user/profile", token, "资料")).data().get("no").asLong();
    }

    private static long perm(ApiStressClient c, String root, String code) {
        for (JsonNode p : ok(c.get("/a/organization/permissions", root, "权限点列表")).data()) {
            if (code.equals(p.get("code").asText())) {
                return p.get("id").asLong();
            }
        }
        throw new AssertionError("没有权限点 " + code);
    }

    private static long subAccountId(ApiStressClient c, String root, String username, String department, List<Long> perms) {
        long id = ok(c.post("/a/organization/sub-accounts", root, Map.of("username", username, "password", "pass1234",
                "realName", department + "账号", "department", department), "建子账号")).dataAsLong();
        ok(c.put("/a/organization/sub-accounts/" + id + "/permissions", root, Map.of("permissionIds", perms), "授权"));
        return id;
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "HTTP 调用");
        return r;
    }
}
