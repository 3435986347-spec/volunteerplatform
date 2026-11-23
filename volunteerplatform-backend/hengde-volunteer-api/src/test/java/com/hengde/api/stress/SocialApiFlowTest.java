package com.hengde.api.stress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 社区核心的 <b>HTTP 全流程</b>（V4 社区核心批）——<b>必跑</b>。
 *
 * <p>真实 multipart 上传帖子图片再发帖；视频走直传签名（存储未启用时的占位地址）；别人刷帖、点赞、评论、关注、看主页；
 * 隐藏帖别人打不开；没验手机号的人看不了、游客不能点赞；两个部门的子账号真登录：本部门发官方帖、删不了别的部门的，
 * 持有全部门权限的宣传部删得了；志愿者 token 调后台被拒。</p>
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
class SocialApiFlowTest {

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
    void postsInteractionsProfilesAndOfficialDepartments() throws Exception {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String root = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        String alice = verified(c, "sa-" + tag);
        long aliceId = idOf(c, alice);
        String bob = verified(c, "sb-" + tag);
        long bobId = idOf(c, bob);

        // ---- 发帖：真实上传图片 ----
        HttpResponse<String> up = upload("/v/files/social-image", alice, "p.png", PNG);
        assertEquals(200, up.statusCode(), up.body());
        String imageUrl = new ObjectMapper().readTree(up.body()).get("data").get("url").asText();
        long post = ok(c.post("/v/social/posts", alice, Map.of("content", "第一帖 " + tag, "imageUrls", List.of(imageUrl)), "发帖"))
                .dataAsLong();
        assertNotEquals(200, neg.post("/v/social/posts", alice,
                Map.of("content", "外链", "imageUrls", List.of("https://evil.example.com/x.png")), "外链图片").code());
        long hidden = ok(c.post("/v/social/posts", alice, Map.of("content", "只给自己看 " + tag, "visibility", 1), "隐藏帖")).dataAsLong();

        // ---- 视频直传签名 → 发帖 ----
        JsonNode presign = ok(c.post("/v/files/social-video/presign?extension=mp4&size=2048", alice, null, "视频签名")).data();
        assertEquals("PUT", presign.get("method").asText());
        long video = ok(c.post("/v/social/posts", alice, Map.of("content", "视频帖 " + tag, "videoUrl", presign.get("url").asText()),
                "视频帖")).dataAsLong();
        assertNotEquals(200, neg.post("/v/files/social-video/presign?extension=exe&size=10", alice, null, "坏扩展名").code());

        // ---- 别人刷、看、赞、评、关注 ----
        List<Long> feed = new ArrayList<>();
        for (JsonNode p : ok(c.get("/v/social/posts?tab=latest&keyword=" + tag + "&page=1&size=20", bob, "搜索")).data().get("records")) {
            feed.add(p.get("id").asLong());
        }
        assertTrue(feed.contains(post) && feed.contains(video), "搜得到：" + feed);
        assertFalse(feed.contains(hidden), "搜不到别人的隐藏帖");
        assertNotEquals(200, neg.get("/v/social/posts/" + hidden, bob, "打开别人的隐藏帖").code());
        JsonNode detail = ok(c.get("/v/social/posts/" + post, bob, "看详情")).data();
        assertEquals(imageUrl, detail.get("mediaUrls").get(0).asText());
        assertFalse(detail.toString().contains("测试志愿者"), "不露真实姓名：" + detail.get("author"));
        assertEquals(true, ok(c.post("/v/social/posts/" + post + "/like", bob, null, "点赞")).data().asBoolean());
        assertEquals(false, ok(c.post("/v/social/posts/" + post + "/like", bob, null, "再点赞")).data().asBoolean());
        long comment = ok(c.post("/v/social/posts/" + post + "/comments", bob, Map.of("content", "写得好"), "评论")).dataAsLong();
        ok(c.post("/v/social/posts/" + post + "/comments", alice, Map.of("content", "谢谢", "parentId", comment), "回复"));
        ok(c.post("/v/social/posts/" + post + "/share", bob, null, "分享"));
        ok(c.post("/v/social/users/" + aliceId + "/follow", bob, null, "关注"));
        JsonNode profile = ok(c.get("/v/social/users/" + aliceId, bob, "看主页")).data();
        assertEquals(1, profile.get("followerCount").asInt());
        assertEquals(1, profile.get("likeCount").asInt());
        assertTrue(profile.get("followedByMe").asBoolean());
        JsonNode after = ok(c.get("/v/social/posts/" + post, alice, "作者看")).data();
        assertEquals(1, after.get("likeCount").asInt());
        assertEquals(2, after.get("commentCount").asInt());
        assertEquals(1, after.get("shareCount").asInt());
        assertTrue(after.get("mine").asBoolean());
        boolean inFollowing = false;
        for (JsonNode p : ok(c.get("/v/social/posts?tab=following&page=1&size=50", bob, "关注页签")).data().get("records")) {
            inFollowing |= p.get("id").asLong() == post;
        }
        assertTrue(inFollowing);
        ok(c.put("/v/social/settings", alice, Map.of("forbidComment", true, "bio", "你好"), "设置禁止评论"));
        assertNotEquals(200, neg.post("/v/social/posts/" + post + "/comments", bob, Map.of("content", "还能评吗"), "禁评后评论").code());
        ok(c.delete("/v/social/comments/" + comment, alice, "作者删别人的评论"));
        ok(c.delete("/v/social/posts/" + video, alice, "删帖"));
        assertNotEquals(200, neg.delete("/v/social/posts/" + post, bob, "删别人的帖").code());

        // ---- 门：没验手机号看不了；游客不能点赞 ----
        String noPhone = c.devLogin("sn-" + tag);
        assertNotEquals(200, neg.get("/v/social/posts?page=1&size=5", noPhone, "没验手机号刷帖").code());
        String guest = ok(c.call("POST", "/v/auth/login/dev", null, Map.of("key", "sg-" + tag, "registered", false), "游客登录"))
                .data().get("token").asText();
        verifyPhone(idOf(c, guest));
        ok(c.get("/v/social/posts/" + post, guest, "游客看帖"));
        assertNotEquals(200, neg.post("/v/social/posts/" + post + "/like", guest, null, "游客点赞").code());

        // ---- 官方帖：两个部门 ----
        long officialPerm = permissionId(c, root, "social:official");
        long officialAllPerm = permissionId(c, root, "social:official-all");
        String org = subAccount(c, root, "so" + tag, "组织部", List.of(officialPerm));
        String publicity = subAccount(c, root, "sp" + tag, "宣传部", List.of(officialPerm, officialAllPerm));
        HttpResponse<String> adminUp = upload("/a/files/upload?dir=social", org, "o.png", PNG);
        assertEquals(200, adminUp.statusCode(), adminUp.body());
        long orgPost = ok(c.post("/a/social/official-posts", org, Map.of("content", "组织部公告 " + tag, "label", "暑期支教队"),
                "组织部发官方帖")).dataAsLong();
        long pubPost = ok(c.post("/a/social/official-posts", publicity, Map.of("content", "宣传部公告 " + tag), "宣传部发官方帖"))
                .dataAsLong();
        JsonNode official = ok(c.get("/v/social/posts/" + orgPost, bob, "志愿者看官方帖")).data();
        assertEquals("官方 · 组织部", official.get("author").get("name").asText());
        assertEquals("暑期支教队", official.get("officialLabel").asText());
        long vComment = ok(c.post("/v/social/posts/" + orgPost + "/comments", bob, Map.of("content", "报名了"), "评论官方帖")).dataAsLong();
        ok(c.post("/a/social/official-posts/" + orgPost + "/comments", org, Map.of("content", "欢迎", "parentId", vComment), "官方回复"));
        assertNotEquals(200, neg.delete("/a/social/official-posts/" + pubPost, org, "组织部删宣传部的帖").code());
        assertNotEquals(200, neg.post("/a/social/official-posts", bob, Map.of("content", "志愿者冒充官方"), "志愿者 token").code());
        ok(c.delete("/a/social/comments/" + vComment, org, "组织部删本部门官方帖下的评论"));
        ok(c.delete("/a/social/official-posts/" + orgPost, publicity, "宣传部删组织部的帖"));
        assertNotEquals(200, neg.get("/v/social/posts/" + orgPost, bob, "删掉之后").code());

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());
    }

    private String verified(ApiStressClient c, String key) {
        String token = c.devLogin(key);
        verifyPhone(idOf(c, token));
        return token;
    }

    private void verifyPhone(long volunteerId) {
        String phone = "135" + String.format("%08d", Math.floorMod(System.nanoTime() + volunteerId, 100_000_000L));
        jdbc.update("UPDATE volunteer SET phone = ?, phone_hash = ? WHERE id = ?",
                cryptoUtil.encrypt(phone), cryptoUtil.hashPhone(phone), volunteerId);
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

    private static String subAccount(ApiStressClient c, String root, String username, String department, List<Long> perms) {
        long id = ok(c.post("/a/organization/sub-accounts", root, Map.of("username", username, "password", "pass1234",
                "realName", department + "负责人", "department", department), "建子账号")).dataAsLong();
        ok(c.put("/a/organization/sub-accounts/" + id + "/permissions", root, Map.of("permissionIds", perms), "授权"));
        return c.adminLogin(username, "pass1234");
    }

    private HttpResponse<String> upload(String path, String token, String filename, byte[] content) throws Exception {
        String boundary = "----socialtest" + System.nanoTime();
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
