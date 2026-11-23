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
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 私信的 <b>HTTP + WebSocket 全流程</b>（V4 私信批）——<b>必跑</b>。
 *
 * <p>真实 multipart 传图发私信 → 对方的 WebSocket <b>当场收到推送</b>（这条链路只有真起 Tomcat 才验得到：
 * 握手要带 token、拦截器不能把 {@code /ws/**} 挡掉、推送的 JSON 要能被客户端读出来）→ 未读与已读 →
 * 清空只对自己 → 投诉 → 只授 {@code social:chat-view} 的子账号看得到聊天记录并处理工单 → 没这个权限的看不到。</p>
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
class SocialChatApiFlowTest {

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
    void chatOverHttpAndWebSocket_thenAdminReadsTheRecord() throws Exception {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String root = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);
        String alice = verified(c, "chat-a-" + tag);
        String bob = verified(c, "chat-b-" + tag);
        long aliceId = idOf(c, alice);
        long bobId = idOf(c, bob);

        // ---- bob 连上 WebSocket（握手带 token）----
        LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .buildAsync(URI.create("ws://localhost:" + port + "/api/ws/social/chat?token=" + bob), new WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            inbox.add(buffer.toString());
                            buffer.setLength(0);
                        }
                        socket.request(1);
                        return null;
                    }
                }).get(30, TimeUnit.SECONDS);
        assertNotNull(ws);

        // ---- alice 传图发私信 ----
        HttpResponse<String> up = upload("/v/files/social-image", alice, "chat.png", PNG);
        assertEquals(200, up.statusCode(), up.body());
        String imageUrl = new ObjectMapper().readTree(up.body()).get("data").get("url").asText();
        long first = ok(c.post("/v/social/chats/" + bobId + "/messages", alice,
                Map.of("content", "在吗 " + tag, "imageUrl", imageUrl), "发私信")).dataAsLong();
        assertNotEquals(200, neg.post("/v/social/chats/" + bobId + "/messages", alice,
                Map.of("content", "外链图", "imageUrl", "https://evil.example.com/x.png"), "外链图片").code());

        // ---- bob 的连接当场收到这条消息 ----
        String pushed = inbox.poll(20, TimeUnit.SECONDS);
        assertNotNull(pushed, "WebSocket 没收到推送");
        JsonNode payload = new ObjectMapper().readTree(pushed);
        assertEquals("message", payload.get("type").asText());
        assertEquals(first, payload.get("data").get("id").asLong());
        assertEquals("在吗 " + tag, payload.get("data").get("content").asText());

        // ---- 未读与已读 ----
        assertEquals(1, ok(c.get("/v/social/chats/unread-count", bob, "未读数")).data().asLong());
        JsonNode list = ok(c.get("/v/social/chats?page=1&size=20", bob, "会话列表")).data().get("records");
        assertTrue(list.toString().contains("在吗 " + tag));
        ok(c.post("/v/social/chats/" + aliceId + "/read", bob, null, "标记已读"));
        assertEquals(0, ok(c.get("/v/social/chats/unread-count", bob, "已读之后")).data().asLong());
        ok(c.post("/v/social/chats/" + aliceId + "/messages", bob, Map.of("content", "在的 " + tag), "回一条"));

        // ---- 清空只对自己 ----
        ok(c.delete("/v/social/chats/" + bobId + "/messages", alice, "清空聊天记录"));
        assertEquals(0, ok(c.get("/v/social/chats/" + bobId + "/messages", alice, "清空之后")).data().size());
        assertEquals(2, ok(c.get("/v/social/chats/" + aliceId + "/messages", bob, "对方那边")).data().size());

        // ---- 投诉 ----
        ok(c.post("/v/social/chats/" + aliceId + "/reports", bob, Map.of("reason", "发广告 " + tag), "投诉"));
        assertNotEquals(200, neg.post("/v/social/chats/" + aliceId + "/reports", bob,
                Map.of("reason", "再投一次"), "重复投诉").code());

        // ---- 后台：只有持 social:chat-view 的账号看得到聊天记录 ----
        long viewerId = ok(c.post("/a/organization/sub-accounts", root, Map.of("username", "cv" + tag, "password", "pass1234",
                "realName", "聊天记录审核员", "department", "监察部"), "建子账号")).dataAsLong();
        long otherId = ok(c.post("/a/organization/sub-accounts", root, Map.of("username", "co" + tag, "password", "pass1234",
                "realName", "别的部门", "department", "宣传部"), "建另一个子账号")).dataAsLong();
        ok(c.put("/a/organization/sub-accounts/" + viewerId + "/permissions", root,
                Map.of("permissionIds", List.of(permissionId(c, root, "social:chat-view"))), "授聊天记录权"));
        ok(c.put("/a/organization/sub-accounts/" + otherId + "/permissions", root,
                Map.of("permissionIds", List.of(permissionId(c, root, "social:post-manage"))), "只授帖子管理"));
        String viewer = c.adminLogin("cv" + tag, "pass1234");
        String other = c.adminLogin("co" + tag, "pass1234");
        assertNotEquals(200, neg.get("/a/social/chats?page=1&size=10", other, "没这个权限").code());
        assertNotEquals(200, neg.get("/a/social/chats?page=1&size=10", bob, "志愿者 token 调后台").code());

        JsonNode chats = ok(c.get("/a/social/chats?volunteerId=" + aliceId + "&page=1&size=10", viewer, "后台看会话")).data().get("records");
        assertEquals(1, chats.size());
        long conversationId = chats.get(0).get("id").asLong();
        JsonNode messages = ok(c.get("/a/social/chats/" + conversationId + "/messages?page=1&size=20", viewer, "后台看消息"))
                .data().get("records");
        assertEquals(2, messages.size(), "志愿者清空过，后台照样看得到两条");
        assertTrue(messages.toString().contains("在吗 " + tag));

        // ---- 工单：处理 + 删一条消息 ----
        JsonNode reports = ok(c.get("/a/social/chat-reports?status=0&page=1&size=20", viewer, "工单队列")).data().get("records");
        long reportId = reports.get(0).get("id").asLong();
        ok(c.post("/a/social/chat-reports/" + reportId + "/handle", viewer,
                Map.of("valid", true, "note", "确实是广告"), "处理工单"));
        assertNotEquals(200, neg.post("/a/social/chat-reports/" + reportId + "/handle", viewer,
                Map.of("valid", false, "note", "再处理一次"), "重复处理").code());
        ok(c.delete("/a/social/chat-messages/" + first, viewer, "删一条违规消息"));
        assertEquals(1, ok(c.get("/v/social/chats/" + aliceId + "/messages", bob, "删了之后")).data().size());
        assertTrue(ok(c.get("/a/social/chats/" + conversationId + "/messages?page=1&size=20", viewer, "后台仍看得到"))
                .data().get("records").toString().contains("\"deleted\":true"), "后台列得出来并标成已删除");

        ws.abort();
        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());
    }

    @Test
    void webSocketHandshakeNeedsAValidToken() throws Exception {
        boolean rejected = false;
        try {
            HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create("ws://localhost:" + port + "/api/ws/social/chat?token=not-a-real-token"),
                            new WebSocket.Listener() {
                            }).get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            rejected = true;
        }
        assertTrue(rejected, "拿一个假 token 也能握上手，那这条连接等于不认人");

        boolean rejectedWithout = false;
        try {
            HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create("ws://localhost:" + port + "/api/ws/social/chat"), new WebSocket.Listener() {
                    }).get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            rejectedWithout = true;
        }
        assertTrue(rejectedWithout, "不带 token 也能握上手");
        assertFalse(false);
    }

    // ================= 造数 =================

    private String verified(ApiStressClient c, String key) {
        String token = c.devLogin(key);
        long id = idOf(c, token);
        String phone = "137" + String.format("%08d", Math.floorMod(System.nanoTime() + id, 100_000_000L));
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
        String boundary = "----chattest" + System.nanoTime();
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
