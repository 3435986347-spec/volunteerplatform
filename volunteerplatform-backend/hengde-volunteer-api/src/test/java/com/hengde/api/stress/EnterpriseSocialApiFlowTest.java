package com.hengde.api.stress;

import cn.hutool.core.util.CreditCodeUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengde.api.HengdeVolunteerApplication;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 爱心企业发帖与赞助商评价的 <b>HTTP 全流程</b>（V4 爱心企业批·社区段）——<b>必跑</b>。
 *
 * <p>企业真实 multipart 上传配图 → 以企业名义发帖 → 帖子进的是<b>同一条审核线</b>（审核员在队列里看得到、能通过）→
 * 志愿者在帖子流与企业主页都看得到、作者是企业名（不是「id 恰好相同的那个志愿者」）→ 兑换赞助商品并领取后评价 →
 * 只授 {@code enterprise:review} 的子账号屏蔽它，公开列表里没了而企业自己与评价人仍看得到 → 恢复。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(classes = HengdeVolunteerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "hengde.auth.dev-login-enabled=true")
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class EnterpriseSocialApiFlowTest {

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52};

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
    void enterprisePostsGoThroughReviewAndSponsorReviewCanBeHidden() throws Exception {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String root = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        Map<String, Object> ent = new HashMap<>();
        ent.put("name", "发帖企业-" + tag);
        ent.put("creditCode", CreditCodeUtil.randomCreditCode());
        ent.put("leaderName", "负责人");
        ent.put("leaderPhone", "158" + String.format("%08d", System.nanoTime() % 100_000_000L));
        ent.put("username", "es_" + tag);
        ent.put("password", "pass1234");
        long entId = ok(c.post("/a/enterprise/enterprises", root, ent, "后台建企业")).dataAsLong();
        String et = ok(c.post("/e/auth/login", null, Map.of("username", "es_" + tag, "password", "pass1234"), "企业登录"))
                .data().get("token").asText();
        String vol = verified(c, "entsocial-" + tag);

        // ---- 企业真实上传配图再发帖；外链与传错目录的都要被拒 ----
        HttpResponse<String> up = upload("/e/files/social-image", et, "logo.png", PNG);
        assertEquals(200, up.statusCode(), up.body());
        String imageUrl = new ObjectMapper().readTree(up.body()).get("data").get("url").asText();
        HttpResponse<String> avatar = upload("/e/files/image", et, "avatar.png", PNG);
        String avatarUrl = new ObjectMapper().readTree(avatar.body()).get("data").get("url").asText();
        long postId = ok(c.post("/e/social/posts", et, Map.of("content", "我们又赞助了一批物资 " + tag,
                "imageUrls", List.of(imageUrl)), "企业发帖")).dataAsLong();
        assertNotEquals(200, neg.post("/e/social/posts", et,
                Map.of("content", "外链图", "imageUrls", List.of("https://evil.example.com/x.png")), "外链图片").code());
        assertNotEquals(200, neg.post("/e/social/posts", et,
                Map.of("content", "头像目录的图", "imageUrls", List.of(avatarUrl)), "传到企业目录的图").code());
        assertNotEquals(200, neg.post("/e/social/posts", vol, Map.of("content", "志愿者拿自己的 token 发企业帖"), "跨端 token").code());

        // ---- 企业帖进的是同一条审核线：审核员在队列里看得到、能通过 ----
        long reviewerId = ok(c.post("/a/organization/sub-accounts", root, Map.of("username", "rv" + tag, "password", "pass1234",
                "realName", "审核员", "department", "宣传部"), "建审核员账号")).dataAsLong();
        ok(c.put("/a/organization/sub-accounts/" + reviewerId + "/permissions", root,
                Map.of("permissionIds", List.of(permissionId(c, root, "social:review"))), "授审核权"));
        ok(c.post("/a/social/reviewers", root, Map.of("adminUserId", reviewerId, "level", 1), "设为第 1 级审核员"));
        String reviewer = c.adminLogin("rv" + tag, "pass1234");
        assertTrue(ok(c.get("/a/social/reviews?page=1&size=100", reviewer, "审核队列")).data().get("records").toString()
                .contains("我们又赞助了一批物资 " + tag), "企业帖必须进审核队列，否则它永远停在待审核");
        ok(c.post("/a/social/reviews/" + postId + "/approve", reviewer, null, "通过企业帖"));

        // ---- 志愿者：帖子流与企业主页都看得到，作者是企业名 ----
        JsonNode seen = ok(c.get("/v/social/posts/" + postId, vol, "志愿者看企业帖")).data();
        assertEquals(3, seen.get("author").get("type").asInt(), "作者类型是企业");
        assertEquals("发帖企业-" + tag, seen.get("author").get("name").asText());
        assertFalse(seen.get("mine").asBoolean(), "别人的帖子不是「我的」");
        assertTrue(ok(c.get("/v/social/posts?tab=latest&keyword=" + tag + "&page=1&size=20", vol, "帖子流")).data()
                .get("records").toString().contains("我们又赞助了一批物资 " + tag));
        assertTrue(ok(c.get("/v/enterprise/enterprises/" + entId + "/posts?page=1&size=20", vol, "企业主页的帖子")).data()
                .get("records").toString().contains("我们又赞助了一批物资 " + tag));
        ok(c.post("/v/social/posts/" + postId + "/like", vol, null, "志愿者点赞企业帖"));

        // ---- 赞助商评价：要一张自己的、已领取的、这家企业赞助的兑换单 ----
        long goodsId = ok(c.post("/e/donate/goods", et, Map.of("name", "赞助帆布袋-" + tag, "detail", "企业赞助",
                "specs", List.of(Map.of("name", "标准", "points", 5, "stock", 5))), "企业新增商品")).dataAsLong();
        ok(c.post("/e/donate/goods/" + goodsId + "/submit", et, null, "提交审核"));
        ok(c.post("/a/donate/goods/" + goodsId + "/approve", root, null, "后台上架"));
        long specId = ok(c.get("/v/enterprise/enterprises/" + entId + "/goods?page=1&size=20", vol, "企业主页商品"))
                .data().get("records").get(0).get("specs").get(0).get("id").asLong();
        ok(c.post("/a/activity/points/adjust", root, Map.of("volunteerId", idOf(c, vol), "changeAmount", 50, "reason", "用例预置",
                "requestId", "esr-" + tag), "给积分"));
        long orderId = ok(c.post("/v/donate/orders", vol, Map.of("specId", specId), "兑换")).data().get("id").asLong();
        assertNotEquals(200, neg.post("/v/enterprise/enterprises/" + entId + "/reviews", vol,
                Map.of("orderId", orderId, "rating", 5, "content", "还没领就评"), "未领取先评").code());
        ok(c.post("/a/donate/orders/" + orderId + "/approve", root, null, "审核兑换单"));
        String code = ok(c.get("/v/donate/orders/" + orderId, vol, "取货码")).data().get("pickupCode").asText();
        ok(c.post("/a/donate/orders/verify", root, Map.of("code", code), "核销"));
        long reviewId = ok(c.post("/v/enterprise/enterprises/" + entId + "/reviews", vol,
                Map.of("orderId", orderId, "rating", 5, "content", "东西不错 " + tag), "评价赞助商")).dataAsLong();
        assertNotEquals(200, neg.post("/v/enterprise/enterprises/" + entId + "/reviews", vol,
                Map.of("orderId", orderId, "rating", 4, "content", "再评一次"), "一单二评").code());
        assertTrue(ok(c.get("/v/enterprise/enterprises/" + entId + "/reviews?page=1&size=10", vol, "企业主页评价"))
                .data().get("records").toString().contains("东西不错 " + tag));
        assertTrue(ok(c.get("/e/enterprise/reviews?page=1&size=10", et, "企业看收到的评价")).data()
                .get("records").toString().contains("东西不错 " + tag));

        // ---- 只授 enterprise:review 的子账号屏蔽 / 恢复 ----
        long moderatorId = ok(c.post("/a/organization/sub-accounts", root, Map.of("username", "mo" + tag, "password", "pass1234",
                "realName", "评价管理员", "department", "监察部"), "建评价管理员")).dataAsLong();
        ok(c.put("/a/organization/sub-accounts/" + moderatorId + "/permissions", root,
                Map.of("permissionIds", List.of(permissionId(c, root, "enterprise:review"))), "授评价管理权"));
        String moderator = c.adminLogin("mo" + tag, "pass1234");
        assertNotEquals(200, neg.post("/a/enterprise/reviews/" + reviewId + "/hide", reviewer,
                Map.of("reason", "没这个权限"), "审核员越权屏蔽").code());
        assertNotEquals(200, neg.get("/a/enterprise/reviews?page=1&size=5", vol, "志愿者 token 调后台").code());
        ok(c.post("/a/enterprise/reviews/" + reviewId + "/hide", moderator, Map.of("reason", "含有不实信息"), "屏蔽"));
        assertFalse(ok(c.get("/v/enterprise/enterprises/" + entId + "/reviews?page=1&size=10", vol, "屏蔽后的公开列表"))
                .data().get("records").toString().contains("东西不错 " + tag), "屏蔽后对外不可见");
        JsonNode mine = ok(c.get("/v/enterprise/reviews/mine?page=1&size=10", vol, "我写过的评价")).data().get("records");
        assertTrue(mine.toString().contains("东西不错 " + tag), "评价人自己仍看得到");
        assertEquals("含有不实信息", mine.get(0).get("hiddenReason").asText(), "并且知道为什么被藏了");
        ok(c.post("/a/enterprise/reviews/" + reviewId + "/show", moderator, null, "恢复"));
        assertTrue(ok(c.get("/v/enterprise/enterprises/" + entId + "/reviews?page=1&size=10", vol, "恢复后的公开列表"))
                .data().get("records").toString().contains("东西不错 " + tag));

        // ---- 企业改帖重回待审核、删自己的帖子 ----
        ok(c.put("/e/social/posts/" + postId, et, Map.of("content", "改了一下文案 " + tag), "企业改帖"));
        JsonNode own = ok(c.get("/e/social/posts?page=1&size=10", et, "企业看自己的帖子")).data().get("records");
        assertEquals("改了一下文案 " + tag, own.get(0).get("content").asText());
        assertEquals("审核中", own.get(0).get("reviewStatusLabel").asText(), "改完重回待审核");
        assertTrue(own.get(0).get("mine").asBoolean(), "企业看自己的帖子是「我的」");
        ok(c.delete("/e/social/posts/" + postId, et, "企业删帖"));
        assertNotEquals(200, neg.get("/v/social/posts/" + postId, vol, "删掉之后打不开").code());

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());
    }

    /** 看社区要已验手机号（四层门的第一层）——dev 登录建的人没有手机号。 */
    private String verified(ApiStressClient c, String key) {
        String token = c.devLogin(key);
        long id = idOf(c, token);
        String phone = "136" + String.format("%08d", Math.floorMod(System.nanoTime() + id, 100_000_000L));
        jdbc.update("UPDATE volunteer SET phone = ?, phone_hash = ? WHERE id = ?",
                cryptoUtil.encrypt(phone), cryptoUtil.hashPhone(phone), id);
        return token;
    }

    private static long idOf(ApiStressClient c, String token) {
        return ok(c.get("/v/user/profile", token, "资料")).data().get("no").asLong();
    }

    private static long permissionId(ApiStressClient c, String root, String code) {
        for (JsonNode p : ok(c.get("/a/organization/permissions", root, "权限点列表")).data()) {
            if (code.equals(p.get("code").asText())) {
                return p.get("id").asLong();
            }
        }
        throw new AssertionError("没有权限点 " + code);
    }

    private HttpResponse<String> upload(String path, String token, String filename, byte[] content) throws Exception {
        String boundary = "----enttest" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "HTTP 调用");
        return r;
    }
}
