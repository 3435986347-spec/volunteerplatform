package com.hengde.api.stress;

import cn.hutool.core.util.CreditCodeUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.hengde.api.HengdeVolunteerApplication;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.common.utils.RedisUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 爱心企业账号的 <b>HTTP 全流程</b>（V4 爱心企业批·账号段）——<b>必跑</b>。
 *
 * <p>服务层证明不了的三件事：① 第三套登录态真的与志愿者端、管理端<b>互不通用</b>（三个方向各试一次）；
 * ② 企业端闸门按<b>当前</b>状态放行——暂停之后旧 token 的下一次请求当场 403、再登录被拒，恢复后能重新登录；
 * ③ 三个后台权限点各管各的：只有审核权的通过得了、暂停不了，只有管理权的反过来，导出单独一个点。</p>
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
class EnterpriseApiFlowTest {

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;
    @Autowired
    private RedisUtil redisUtil;

    @Test
    void registerAuditPauseResumeExportDelete_withThreeWayTokenIsolation() throws Exception {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String root = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        Map<String, Long> perms = new HashMap<>();
        for (JsonNode p : ok(c.get("/a/organization/permissions", root, "权限点列表")).data()) {
            perms.put(p.get("code").asText(), p.get("id").asLong());
        }
        String auditor = account(c, root, "ea" + tag, perms.get("enterprise:audit"));
        String manager = account(c, root, "em" + tag, perms.get("enterprise:manage"));
        String exporter = account(c, root, "ex" + tag, perms.get("enterprise:export"));

        // ---- 企业自助注册 ----
        String phone = "158" + String.format("%08d", System.nanoTime() % 100_000_000L);
        ok(c.post("/e/auth/sms/codes", null, Map.of("phone", phone, "scene", "enterprise-register"), "发注册验证码（免登录）"));
        String code = String.valueOf(redisUtil.get("sms:code:enterprise-register:" + phone));
        String username = "ent_" + tag;
        Map<String, Object> form = new HashMap<>();
        form.put("name", "雷州爱心商行-" + tag);
        form.put("creditCode", CreditCodeUtil.randomCreditCode());
        form.put("intro", "长期赞助志愿者积分商品");
        form.put("address", "雷州市新城大道 8 号");
        form.put("contactPhone", "0759-6666666");
        form.put("leaderName", "商行负责人");
        form.put("leaderPhone", phone);
        form.put("smsCode", code);
        form.put("username", username);
        form.put("password", "pass1234");
        long id = ok(c.post("/e/auth/register", null, form, "注册（免登录）")).dataAsLong();

        JsonNode login = ok(c.post("/e/auth/login", null, Map.of("username", username, "password", "pass1234"), "待审核也能登录")).data();
        assertEquals(0, login.get("status").asInt());
        String ent = login.get("token").asText();
        assertEquals(0, ok(c.get("/e/enterprise/profile", ent, "看自己的资料")).data().get("status").asInt());

        // ---- 三套登录态互不通用 ----
        String vol = c.devLogin("ent-vol-" + tag);
        assertNotEquals(200, neg.get("/v/user/profile", ent, "企业 token 调志愿者端").code());
        assertNotEquals(200, neg.get("/a/auth/me", ent, "企业 token 调管理端").code());
        assertNotEquals(200, neg.get("/e/enterprise/profile", vol, "志愿者 token 调企业端").code());
        assertNotEquals(200, neg.get("/e/enterprise/profile", root, "管理端 token 调企业端").code());
        assertFalse(listed(c, vol, id), "待审核的不公开");

        // ---- 审核：只有审核权的能通过、暂停不了 ----
        JsonNode queue = ok(c.get("/a/enterprise/enterprises?status=0&page=1&size=100", auditor, "入驻审核队列")).data().get("records");
        assertTrue(queue.toString().contains(username), queue.toString());
        assertNotEquals(200, neg.post("/a/enterprise/enterprises/" + id + "/approve", manager, null, "管理权去审核").code());
        ok(c.post("/a/enterprise/enterprises/" + id + "/approve", auditor, null, "审核通过"));
        assertTrue(listed(c, vol, id), "通过后志愿者看得到");
        JsonNode card = ok(c.get("/v/enterprise/enterprises/" + id, vol, "企业主页")).data();
        assertFalse(card.has("creditCode") || card.has("leaderPhone") || card.has("username"), "公开主页不露私密字段：" + card);

        // ---- 暂停当场生效、恢复后能重新登录 ----
        assertNotEquals(200, neg.post("/a/enterprise/enterprises/" + id + "/pause", auditor, Map.of("reason", "x"), "审核权去暂停").code());
        ok(c.post("/a/enterprise/enterprises/" + id + "/pause", manager, Map.of("reason", "赞助商品长期缺货"), "暂停"));
        assertNotEquals(200, neg.get("/e/enterprise/profile", ent, "暂停后旧 token").code());
        ApiStressClient.Resp relogin = neg.post("/e/auth/login", null, Map.of("username", username, "password", "pass1234"), "暂停后再登录");
        assertNotEquals(200, relogin.code());
        assertTrue(String.valueOf(relogin.message()).contains("已暂停"), String.valueOf(relogin.message()));
        assertFalse(listed(c, vol, id), "暂停的不公开");
        ok(c.post("/a/enterprise/enterprises/" + id + "/resume", manager, null, "恢复"));
        String ent2 = ok(c.post("/e/auth/login", null, Map.of("username", username, "password", "pass1234"), "恢复后登录")).data().get("token").asText();
        ok(c.get("/e/enterprise/profile", ent2, "恢复后可用"));

        // ---- 导出单独一个权限点 ----
        assertFalse(raw("/a/enterprise/enterprises/export?keyword=" + username, auditor).headers().firstValue("Content-Type").orElse("")
                .contains("spreadsheetml"), "审核权导不了");
        HttpResponse<byte[]> xlsx = raw("/a/enterprise/enterprises/export?keyword=" + username, exporter);
        assertEquals(200, xlsx.statusCode());
        assertTrue(xlsx.headers().firstValue("Content-Type").orElse("").contains("spreadsheetml"));

        // ---- 删除即踢出 ----
        ok(c.delete("/a/enterprise/enterprises/" + id, manager, "删除"));
        assertNotEquals(200, neg.get("/e/enterprise/profile", ent2, "删除后旧 token").code());
        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());
    }

    private boolean listed(ApiStressClient c, String token, long id) {
        for (JsonNode r : ok(c.get("/v/enterprise/enterprises?page=1&size=100", token, "爱心企业列表")).data().get("records")) {
            if (r.get("id").asLong() == id) {
                return true;
            }
        }
        return false;
    }

    private String account(ApiStressClient c, String root, String username, long permId) {
        long accountId = ok(c.post("/a/organization/sub-accounts", root, Map.of("username", username,
                "password", "pass1234", "realName", "宣传部" + username, "department", "宣传部"), "建子账号")).dataAsLong();
        ok(c.put("/a/organization/sub-accounts/" + accountId + "/permissions", root, Map.of("permissionIds", List.of(permId)), "授权"));
        return c.adminLogin(username, "pass1234");
    }

    private HttpResponse<byte[]> raw(String path, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", token)
                .GET().build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "HTTP 调用");
        return r;
    }
}
