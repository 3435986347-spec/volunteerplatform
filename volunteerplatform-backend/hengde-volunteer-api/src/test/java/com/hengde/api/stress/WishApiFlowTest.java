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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 圆梦微心愿的 <b>HTTP 全流程</b>（V3 微心愿批）——<b>必跑</b>，不打 stress 标签。
 *
 * <p>理由同 {@code BookDonationApiFlowTest}：服务层用例证明规则对，这一条证明<b>接口真的接得上</b>——
 * 路径、{@code @SaCheckPermission}、两套登录态、Long 转字符串、xlsx 响应头，以及「认领后才下发全文」
 * 在 HTTP 出参上确实成立（服务层打了 *，序列化层不能再把明文带出去）。</p>
 *
 * <p>开发登录造的账号没有手机号，而看心愿要已验手机号——用例直接给它补上手机号密文与哈希（与手机号登录的结果一致）。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(classes = HengdeVolunteerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hengde.auth.dev-login-enabled=true",
                "hengde.donate.wish.recv-address=雷州市某路 1 号 恒德协会办公室"})
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class WishApiFlowTest {

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
    void claimToRealizeOverHttp() throws Exception {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        // ---- 后台：受赠单位 → 单独上传心愿；模板是 xlsx ----
        long org = ok(c.post("/a/donate/recipient-orgs", admin, Map.of("name", "心愿接口小学-" + tag), "建单位"))
                .dataAsLong();
        String title = "接口心愿-" + tag;
        long wishId = ok(c.post("/a/donate/wishes", admin, Map.of(
                "title", title, "content", "想要一双球鞋", "childName", "陈小花", "childGender", 2, "childAge", 8,
                "childSchool", "雷州市第二小学", "childGrade", "二年级", "reportOrgId", org), "上传心愿")).dataAsLong();
        HttpResponse<byte[]> template = raw("/a/donate/wishes/import-template", admin);
        assertEquals(200, template.statusCode());
        assertTrue(template.headers().firstValue("Content-Type").orElse("").contains("spreadsheetml"));

        // ---- 志愿者：没验手机号看不了；补上手机号后心愿池打 * ----
        String donorKey = "wf-" + tag;
        String donor = c.devLogin(donorKey);
        ApiStressClient.Resp denied = neg.get("/v/donate/wishes", donor, "未验手机号");
        assertEquals(400, denied.code());
        assertTrue(denied.message().contains("验证手机号"), denied.message());
        String donorPhone = bindPhone(donorKey);
        long donorId = volunteerId(donorKey);

        JsonNode pool = ok(c.get("/v/donate/wishes?tab=0&keyword=" + tag + "&page=1&size=10", donor, "心愿池"))
                .data().get("records");
        assertEquals(1, pool.size());
        assertEquals("陈**", pool.get(0).get("childName").asText());
        assertTrue(pool.get(0).get("masked").asBoolean());
        // 出参里 null 字段被整个省略（Jackson NON_NULL），所以判「不存在或为 null」而不是 get(...).isNull()
        assertTrue(pool.get(0).path("recvAddress").isMissingNode() || pool.get(0).path("recvAddress").isNull(),
                "没认领看不到接收地址：" + pool.get(0));

        // ---- 认领：出参里才有全文与接收地址 ----
        JsonNode claimed = ok(c.post("/v/donate/wishes/" + wishId + "/claim", donor, null, "认领")).data();
        assertEquals("陈小花", claimed.get("childName").asText());
        assertEquals("雷州市第二小学", claimed.get("childSchool").asText());
        assertEquals("雷州市某路 1 号 恒德协会办公室", claimed.get("recvAddress").asText());
        assertTrue(claimed.get("id").isTextual(), "Long 一律序列化成字符串");

        // ---- 寄出 → 后台到货 / 核对 / 专属码（面单上有心愿编号与受捐学生） → 实现 ----
        JsonNode shipment = ok(c.post("/v/donate/wishes/" + wishId + "/shipments", donor, Map.of(
                "expressCode", "shunfeng", "expressNo", "SF" + System.nanoTime(),
                "items", List.of(Map.of("name", "球鞋", "itemType", 9, "quantity", 1))), "登记寄出")).data();
        String shipmentId = shipment.get("id").asText();
        assertTrue(shipment.get("campaignTitle").asText().startsWith("微心愿 "), shipment.toString());
        String itemId = shipment.get("items").get(0).get("id").asText();
        ok(c.post("/a/donate/shipments/" + shipmentId + "/arrive", admin, null, "到货"));
        ok(c.post("/a/donate/shipments/" + shipmentId + "/check", admin, Map.of("results", List.of(
                Map.of("itemId", itemId, "qualified", true))), "核对"));
        JsonNode label = ok(c.post("/a/donate/items/" + itemId + "/barcode", admin, null, "生成专属码")).data();
        assertEquals("陈小花", label.get("childName").asText());
        assertTrue(label.get("wishNo").asText().startsWith("HDW"));
        ok(c.post("/a/donate/wishes/" + wishId + "/realize", admin,
                Map.of("images", List.of("https://cdn.example.com/w1.jpg")), "实现"));

        // ---- 后台详情：认领人电话；导出是 xlsx ----
        JsonNode detail = ok(c.get("/a/donate/wishes/" + wishId, admin, "后台详情")).data();
        assertEquals(2, detail.get("wish").get("status").asInt());
        assertEquals(donorPhone, detail.get("claims").get(0).get("claimerPhone").asText());
        HttpResponse<byte[]> xlsx = raw("/a/donate/wishes/export?keyword=" + tag, admin);
        assertEquals(200, xlsx.statusCode());
        assertTrue(xlsx.headers().firstValue("Content-Type").orElse("").contains("spreadsheetml"));
        assertTrue(xlsx.body().length > 1000);

        // ---- 志愿者：微心愿中心、站内提示、微心愿排行（总榜） ----
        JsonNode mine = ok(c.get("/v/donate/wishes/mine?page=1&size=10", donor, "微心愿中心")).data().get("records");
        assertEquals(1, mine.get(0).get("status").asInt(), "认领已实现");
        assertEquals(1, mine.get(0).get("shipments").size());
        assertEquals("https://cdn.example.com/w1.jpg", mine.get(0).get("wish").get("feedbackImages").get(0).asText());
        JsonNode notes = ok(c.get("/v/notifications?page=1&size=10", donor, "站内提示")).data().get("records");
        assertTrue(notes.toString().contains("微心愿"), notes.toString());
        JsonNode ranking = ok(c.get("/v/honor/rankings?rankType=4&periodType=3&limit=100", donor, "微心愿排行")).data();
        assertEquals("微心愿排行", ranking.get("rankTypeLabel").asText());
        boolean onBoard = false;
        for (JsonNode e : ranking.get("entries")) {
            onBoard |= Long.toString(donorId).equals(e.get("volunteerId").asText());
        }
        assertTrue(onBoard, "圆了心愿的人应出现在微心愿排行上：" + ranking);

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());

        // ---- 负向：旁人只看得到打 * 的、取消不了别人的认领；志愿者 token 过不了 /a ----
        String strangerKey = "ws-" + tag;
        String stranger = neg.devLogin(strangerKey);
        bindPhone(strangerKey);
        JsonNode seen = ok(neg.get("/v/donate/wishes/" + wishId, stranger, "旁人看")).data();
        assertTrue(seen.get("masked").asBoolean());
        assertFalse(seen.get("childName").asText().contains("小花"));
        assertEquals(0, seen.get("feedbackImages").size(), "发放照片只给认领人");
        ApiStressClient.Resp cancel = neg.delete("/v/donate/wishes/" + wishId + "/claim", stranger, "取消别人的认领");
        assertEquals(400, cancel.code());
        ApiStressClient.Resp crossRole = neg.get("/a/donate/wishes", stranger, "志愿者 token 调后台");
        assertNotEquals(200, crossRole.code());
    }

    /** 给开发登录的账号补上手机号（密文 + 哈希），与手机号登录建出来的账号一致。 */
    private String bindPhone(String devKey) {
        String phone = String.format("139%08d", Math.floorMod(System.nanoTime(), 100_000_000L));
        jdbc.update("UPDATE volunteer SET phone = ?, phone_hash = ? WHERE openid = ?",
                cryptoUtil.encrypt(phone), cryptoUtil.hashPhone(phone), "dev:" + devKey);
        return phone;
    }

    private long volunteerId(String devKey) {
        return jdbc.queryForObject("SELECT id FROM volunteer WHERE openid = ?", Long.class, "dev:" + devKey);
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "HTTP 调用");
        return r;
    }

    private HttpResponse<byte[]> raw(String path, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", token)
                .GET().build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofByteArray());
    }
}
