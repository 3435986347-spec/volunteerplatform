package com.hengde.api.stress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengde.api.HengdeVolunteerApplication;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 个人中心补全的 <b>HTTP 全流程</b>（V4 个人中心补全批）——<b>必跑</b>。
 *
 * <p>走一遍：地址增改删置顶；后台设置「联系客服」、志愿者读到；订阅通知关掉一个可关的、关不可关的被拒；
 * 真实上传一张签名图再设为签名板；安全中心的意见反馈（问卷场景 4）直接交两次；资料里带着年级提示与签名板字段。</p>
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
class PersonalCenterApiFlowTest {

    /** 最小的合法 PNG 文件头（魔数校验只看头部）。 */
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52};

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;

    @Test
    void addresses_centerContent_notifyPreferences_padSignature_feedback() throws Exception {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);
        String me = c.devLogin("center-" + tag);

        // ---- 地址 ----
        long a = ok(c.post("/v/user/addresses", me, address("张三", "新城大道 1 号"), "新增地址")).dataAsLong();
        long b = ok(c.post("/v/user/addresses", me, address("李四", "西湖大道 2 号"), "新增地址")).dataAsLong();
        ok(c.post("/v/user/addresses/" + b + "/top", me, null, "置顶"));
        ok(c.put("/v/user/addresses/" + a, me, address("张三丰", "新城大道 3 号"), "修改地址"));
        JsonNode list = ok(c.get("/v/user/addresses", me, "我的地址")).data();
        assertEquals(b, list.get(0).get("id").asLong(), "置顶在最前：" + list);
        assertEquals("13800000000", list.get(0).get("recvPhone").asText());
        ok(c.delete("/v/user/addresses/" + a, me, "删除地址"));
        String stranger = neg.devLogin("center-stranger-" + tag);
        assertNotEquals(200, neg.delete("/v/user/addresses/" + b, stranger, "删别人的地址").code());

        // ---- 联系客服：后台设置、志愿者读 ----
        ok(c.put("/a/user/center-contents/customer-service", admin,
                Map.of("title", "联系客服", "body", "电话 0759-1234567（工作日 9:00–17:30）"), "后台设置客服"));
        JsonNode cs = ok(c.get("/v/user/center-contents/customer-service", me, "志愿者看客服")).data();
        assertTrue(cs.get("body").asText().contains("0759-1234567"), cs.toString());
        assertNotEquals(200, neg.put("/a/user/center-contents/insurance", me, Map.of("title", "x"), "志愿者 token 设置").code());

        // ---- 订阅通知 ----
        JsonNode prefs = ok(c.get("/v/user/notify-preferences", me, "我的订阅")).data();
        assertTrue(prefs.size() >= 7, prefs.toString());
        ok(c.put("/v/user/notify-preferences", me, Map.of("topic", "ACTIVITY_REMINDER", "smsEnabled", false), "关活动提醒"));
        assertNotEquals(200, neg.put("/v/user/notify-preferences", me,
                Map.of("topic", "REWARD_PUNISH", "smsEnabled", false), "关奖惩提醒").code());
        boolean reminderOff = false;
        for (JsonNode p : ok(c.get("/v/user/notify-preferences", me, "再看订阅")).data()) {
            if ("ACTIVITY_REMINDER".equals(p.get("topic").asText())) {
                reminderOff = !p.get("smsEnabled").asBoolean();
            }
        }
        assertTrue(reminderOff);

        // ---- 签名板：真实上传再设置 ----
        HttpResponse<String> up = upload("/v/files/profile-image?dir=signature", me, "sign.png", PNG);
        assertEquals(200, up.statusCode(), up.body());
        String signUrl = new ObjectMapper().readTree(up.body()).get("data").get("url").asText();
        ok(c.put("/v/user/pad-signature", me, Map.of("url", signUrl), "设置签名板"));
        JsonNode profile = ok(c.get("/v/user/profile", me, "我的资料")).data();
        assertEquals(signUrl, profile.get("padSignatureUrl").asText());
        assertTrue(profile.has("gradePromptPending"), profile.toString());
        assertNotEquals(200, neg.put("/v/user/pad-signature", me, Map.of("url", "https://evil.example.com/s.png"),
                "外链当签名").code());

        // ---- 意见反馈（问卷场景 4）----
        for (JsonNode f : ok(c.get("/a/organization/forms?scene=4&status=1&page=1&size=50", admin, "收集中的反馈问卷"))
                .data().get("records")) {
            ok(c.post("/a/organization/forms/" + f.get("id").asText() + "/close", admin, null, "停掉旧的"));
        }
        long formId = ok(c.post("/a/organization/forms", admin, Map.of("scene", 4, "title", "意见反馈-" + tag,
                "questions", List.of(Map.of("type", 5, "title", "您的意见"))), "建反馈问卷")).dataAsLong();
        ok(c.post("/a/organization/forms/" + formId + "/publish", admin, null, "发布反馈问卷"));
        JsonNode current = ok(c.get("/v/organization/forms/scenes/4/current", me, "当前反馈问卷")).data();
        String qid = current.get("questions").get(0).get("id").asText();
        for (int i = 0; i < 2; i++) {
            ok(c.post("/v/organization/forms/" + formId + "/submissions", me,
                    Map.of("answers", List.of(Map.of("questionId", qid, "value", "第 " + i + " 条意见"))), "交反馈"));
        }
        JsonNode listed = ok(c.get("/v/organization/forms?page=1&size=100", me, "问卷列表")).data().get("records");
        assertFalse(listed.toString().contains("意见反馈-" + tag), "意见反馈不混进问卷列表");
        ok(c.post("/a/organization/forms/" + formId + "/close", admin, null, "停掉反馈问卷"));

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());
    }

    private static Map<String, Object> address(String name, String detail) {
        return Map.of("recvName", name, "recvPhone", "13800000000", "region", "广东省湛江市雷州市", "detail", detail);
    }

    private HttpResponse<String> upload(String path, String token, String filename, byte[] content) throws Exception {
        String boundary = "----centertest" + System.nanoTime();
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
