package com.hengde.api.stress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengde.api.HengdeVolunteerApplication;
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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 奖惩分页与申诉的整条链路——走真实 HTTP，<b>必跑</b>。
 *
 * <p>对应小程序侧 2026-10-13 验收单第 5 项：分页对象带全 records / total / page / size / pages、
 * 第 2 页上有 {@code appealable=true} 的记录、{@code /v/files/appeal-image} 不带 dir 也能传、
 * 申诉理由 1024 字收 1025 字拒、凭证 6 张收 7 张拒、重复提交拒、提交后状态跟着变、
 * 只有受理权的后台账号只看得到进了申诉流程的单。</p>
 *
 * <p>另钉住一条本批补上的缺口：<b>凭证只收经上传接口传上来的图</b>，外链提交不了——不然受理人在后台点开的是别人的页面。
 * 只有走真实上传拿到的 URL 再提交，才证明得了「上传端点产出的地址」与「提交时的校验」是同一个目录、对得上。</p>
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
class RewardPunishAppealApiFlowTest {

    /** 1×1 透明 PNG：上传校验看文件头，随便几个字节过不了 */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void pagedRecords_thenAppealWithUploadedEvidence() throws Exception {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String root = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);
        String key = "appeal-" + tag;
        String me = c.devLogin(key);
        long vid = jdbc.queryForObject("SELECT id FROM volunteer WHERE openid = ?", Long.class, "dev:" + key);

        // 超管持终审权：开即通过，处罚当场生效、进入 7 天申诉期
        for (int i = 0; i < 25; i++) {
            ok(c.post("/a/honor/reward-punishes", root, punish(vid, i), "开处罚"));
        }

        // ---- 分页对象五个字段齐全，第 2 页有可申诉的记录 ----
        JsonNode page2 = ok(c.get("/v/honor/reward-punishes?page=2&size=10", me, "第 2 页")).data();
        for (String f : List.of("records", "total", "page", "size", "pages")) {
            assertTrue(page2.has(f), "分页对象缺 " + f + "：" + page2);
        }
        assertEquals(25, page2.get("total").asLong());
        assertEquals(2, page2.get("page").asLong());
        assertEquals(10, page2.get("size").asLong());
        assertEquals(3, page2.get("pages").asLong());
        assertEquals(10, page2.get("records").size());
        JsonNode target = null;
        for (JsonNode r : page2.get("records")) {
            if (r.get("appealable").asBoolean()) {
                target = r;
                break;
            }
        }
        assertTrue(target != null, "第 2 页上要有 appealable=true 的记录：" + page2);
        long ticket = target.get("id").asLong();
        assertEquals(5, ok(c.get("/v/honor/reward-punishes?page=3&size=10", me, "第 3 页")).data()
                .get("records").size());

        // ---- 上传凭证：不带 dir，拿回可用的 URL ----
        HttpResponse<String> up = upload("/v/files/appeal-image", me, "proof.png", PNG);
        assertEquals(200, up.statusCode(), up.body());
        String url = JSON.readTree(up.body()).get("data").get("url").asText();
        assertTrue(url.contains("/appeal/"), url);

        String path = "/v/honor/reward-punishes/" + ticket + "/appeal";
        // 1025 字被拒、7 张被拒、外链被拒——每次被拒之后单子都还能申诉
        assertNotEquals(200, neg.post(path, me, appeal("字".repeat(1025), List.of(url)), "1025 字").code());
        List<String> seven = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            seven.add(url);
        }
        ApiStressClient.Resp tooMany = neg.post(path, me, appeal("凭证太多", seven), "7 张");
        assertNotEquals(200, tooMany.code());
        assertTrue(tooMany.message().contains("6"), tooMany.message());
        ApiStressClient.Resp outside = neg.post(path, me, appeal("外链", List.of("https://evil.example/x.png")), "外链");
        assertNotEquals(200, outside.code());
        assertTrue(outside.message().contains("上传接口"), outside.message());

        // 1024 字 + 6 张：收
        List<String> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(url);
        }
        ok(c.post(path, me, appeal("申".repeat(1024), six), "1024 字 + 6 张"));

        // 提交后状态跟着变：申诉中、不再可申诉，凭证原样读回
        JsonNode after = find(ok(c.get("/v/honor/reward-punishes?page=2&size=10", me, "提交后")).data(), ticket);
        assertEquals(1, after.get("appealStatus").asInt(), after.toString());
        assertFalse(after.get("appealable").asBoolean(), after.toString());
        assertEquals(6, after.get("appealImageUrls").size());

        // 重复提交拒绝，且不覆盖第一次的理由
        ApiStressClient.Resp again = neg.post(path, me, appeal("改口了", List.of(url)), "重复提交");
        assertNotEquals(200, again.code());
        assertEquals(1024, jdbc.queryForObject("SELECT CHAR_LENGTH(appeal_reason) FROM honor_reward_punish WHERE id = ?",
                Integer.class, ticket));

        // ---- 只有申诉受理权的后台账号：只看得到进了申诉流程的那一张 ----
        String appealDesk = subAccount(c, root, "ad" + tag, permId(c, root, "honor:reward-punish-appeal"));
        JsonNode desk = ok(neg.get("/a/honor/reward-punishes?page=1&size=50&volunteerId=" + vid, appealDesk,
                "受理人看列表")).data();
        assertEquals(1, desk.get("total").asLong(), desk.toString());
        assertEquals(ticket, desk.get("records").get(0).get("id").asLong());
        JsonNode all = ok(c.get("/a/honor/reward-punishes?page=1&size=50&volunteerId=" + vid, root, "超管看列表")).data();
        assertEquals(25, all.get("total").asLong());
    }

    // ---------------- helpers ----------------

    private static Map<String, Object> punish(long vid, int i) {
        Map<String, Object> body = new HashMap<>();
        body.put("volunteerId", vid);
        body.put("type", 2);
        body.put("category", "违规");
        body.put("title", "测试处罚-" + i);
        return body;
    }

    private static Map<String, Object> appeal(String reason, List<String> images) {
        Map<String, Object> body = new HashMap<>();
        body.put("reason", reason);
        body.put("imageUrls", images);
        return body;
    }

    private static JsonNode find(JsonNode page, long id) {
        for (JsonNode r : page.get("records")) {
            if (r.get("id").asLong() == id) {
                return r;
            }
        }
        throw new AssertionError("这一页里没有 " + id + "：" + page);
    }

    private HttpResponse<String> upload(String path, String token, String filename, byte[] content) throws Exception {
        String boundary = "----appealtest" + System.nanoTime();
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

    private long permId(ApiStressClient c, String root, String code) {
        for (JsonNode p : ok(c.get("/a/organization/permissions", root, "权限点")).data()) {
            if (code.equals(p.get("code").asText())) {
                return p.get("id").asLong();
            }
        }
        throw new AssertionError("权限点不存在：" + code);
    }

    private String subAccount(ApiStressClient c, String root, String username, long... perms) {
        long accountId = ok(c.post("/a/organization/sub-accounts", root, Map.of("username", username,
                "password", "pass1234", "realName", "受理测试", "department", "秘书部"), "建子账号")).dataAsLong();
        List<Long> ids = new ArrayList<>();
        for (long p : perms) {
            ids.add(p);
        }
        ok(c.put("/a/organization/sub-accounts/" + accountId + "/permissions", root, Map.of("permissionIds", ids), "授权"));
        return c.adminLogin(username, "pass1234");
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "HTTP 调用");
        return r;
    }
}
