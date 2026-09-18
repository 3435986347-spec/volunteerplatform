package com.hengde.api.stress;

import com.fasterxml.jackson.databind.JsonNode;
import com.hengde.api.HengdeVolunteerApplication;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公益捐书的 <b>HTTP 全流程</b>（V3 捐书批）——<b>必跑</b>，不打 stress 标签。
 *
 * <p>服务层用例证明业务规则对；这一条证明<b>接口真的接得上</b>：路径、{@code @SaCheckPermission}、
 * Sa-Token 两套登录态、Jackson 出入参（Long 转字符串、时间空格格式）、Excel 导出的响应头。
 * 契约脚本只看注解文本，证明不了「端点真的注册进了 Spring」（V2 第 5 批 javadoc 吞掉 {@code @GetMapping} 那一课），
 * 真打一遍 HTTP 才证明得了。</p>
 *
 * <p>这也是 V3规划·捐书批要求的「内部用的扫码测试脚本」：前端不在本轮，扫码动作连手工验证的路径都没有，
 * 这一条就是按一个真实操作员的顺序把十步扫一遍。</p>
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
class BookDonationApiFlowTest {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;

    @Test
    void theTenStepsOverHttp() throws Exception {
        ApiStressClient c = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        // ---- 后台准备：受赠单位、活动（草稿 → 发布） ----
        long org = ok(c.post("/a/donate/recipient-orgs", admin, Map.of("name", "接口用例小学-" + tag), "建单位")).dataAsLong();
        long campaign = ok(c.post("/a/donate/book-campaigns", admin, Map.of(
                "title", "接口用例捐书-" + tag,
                "startTime", LocalDateTime.now().minusDays(1).format(FMT),
                // 入参兼容 ISO 的 T（后台 datetime-local 送的就是它），见 JacksonConfig 宽松反序列化
                "endTime", LocalDateTime.now().plusDays(7).withNano(0).toString(),
                "recvPhone", "0759-1234567",
                "recvAddress", "雷州市某路 1 号 转 "), "建活动")).dataAsLong();
        ok(c.post("/a/donate/book-campaigns/" + campaign + "/publish", admin, null, "发布"));

        // ---- 捐赠人：看收件地址 → 登记寄出 ----
        String donor = c.devLogin("book-flow-" + tag);
        JsonNode detail = ok(c.get("/v/donate/book-campaigns/" + campaign, donor, "活动详情")).data();
        assertEquals("雷州市某路 1 号 转 " + detail.get("recvName").asText(), detail.get("recvAddress").asText(),
                "地址 = 预留地址 + 捐赠人姓名（Row 17 原文格式）");
        assertTrue(detail.get("open").asBoolean());
        assertFalse(detail.get("startTime").asText().contains("T"), "出参时间统一空格格式");
        JsonNode companies = ok(c.get("/v/donate/express-companies", donor, "快递公司")).data();
        assertTrue(companies.toString().contains("shunfeng"));

        String expressNo = "SF" + System.nanoTime();
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("name", "小王子", "itemType", 1, "quantity", 2, "catalogBarcode", "9787020042494"));
        items.add(Map.of("name", "旧球拍", "itemType", 3, "quantity", 1));
        JsonNode shipment = ok(c.post("/v/donate/book-campaigns/" + campaign + "/shipments", donor, Map.of(
                "expressCode", "shunfeng", "expressNo", expressNo, "donorOrg", "接口用例单位", "items", items),
                "登记寄出")).data();
        String shipmentId = shipment.get("id").asText();
        assertTrue(shipment.get("id").isTextual(), "Long 一律序列化成字符串，防 JS 丢精度");
        Map<String, String> itemIds = new HashMap<>();
        shipment.get("items").forEach(i -> itemIds.put(i.get("name").asText(), i.get("id").asText()));

        // ---- 后台扫码：识别 → 到货 → 核对 ----
        JsonNode scan = ok(c.get("/a/donate/scan?code=" + expressNo.toLowerCase(), admin, "扫快递单号")).data();
        assertEquals("SHIPMENT", scan.get("kind").asText());
        assertEquals(shipmentId, scan.get("id").asText());
        ok(c.post("/a/donate/shipments/" + shipmentId + "/arrive", admin, null, "到货"));
        ok(c.post("/a/donate/shipments/" + shipmentId + "/check", admin, Map.of("results", List.of(
                Map.of("itemId", itemIds.get("小王子"), "qualified", true),
                Map.of("itemId", itemIds.get("旧球拍"), "qualified", false, "remark", "拍面断裂"))), "核对"));

        // ---- 专属码 → 建箱 → 装箱 → 送达 ----
        JsonNode label = ok(c.post("/a/donate/items/" + itemIds.get("小王子") + "/barcode", admin, null, "生成专属码")).data();
        String code = label.get("exclusiveCode").asText();
        assertTrue(label.get("barcode").asText().startsWith("data:image/png;base64,"));
        JsonNode box = ok(c.post("/a/donate/boxes", admin, Map.of("campaignId", campaign), "建箱")).data();
        String boxId = box.get("id").asText();
        assertEquals("BOX", ok(c.get("/a/donate/scan?code=" + box.get("boxCode").asText(), admin, "扫箱码")).data()
                .get("kind").asText());
        ok(c.post("/a/donate/boxes/" + boxId + "/pack", admin, Map.of("code", code), "装箱"));
        JsonNode delivered = ok(c.post("/a/donate/boxes/" + boxId + "/deliver", admin,
                Map.of("recipientOrgId", org), "送达")).data();
        assertEquals(1, delivered.get("status").asInt());

        // ---- 捐赠人：捐书记录、运单轨迹、物流（未开通） ----
        JsonNode mine = ok(c.get("/v/donate/items/mine?page=1&size=20", donor, "我的捐书记录")).data().get("records");
        JsonNode book = find(mine, "小王子");
        assertTrue(book.get("recipientOrgName").asText().startsWith("接口用例小学-"));
        assertEquals("合格", book.get("auditLabel").asText());
        assertEquals("不合格", find(mine, "旧球拍").get("auditLabel").asText());
        JsonNode myShipment = ok(c.get("/v/donate/shipments/" + shipmentId, donor, "我的运单")).data();
        assertTrue(myShipment.get("traces").size() >= 6, "登记、到货、两件核对、生成码、装箱、送达");
        JsonNode track = ok(c.get("/v/donate/shipments/" + shipmentId + "/track", donor, "物流")).data();
        assertFalse(track.get("available").asBoolean(), "测试环境未开通快递100：如实说未开通，不报错");

        // ---- 不合格退回：捐赠人交地址 → 后台寄回 ----
        ok(c.post("/v/donate/shipments/" + shipmentId + "/return-address", donor, Map.of(
                "name", "捐赠人", "phone", "13900002222", "address", "湛江市某小区"), "交退回地址"));
        ok(c.post("/a/donate/shipments/" + shipmentId + "/return", admin, Map.of(
                "expressCode", "zhongtong", "expressNo", "ZT" + System.nanoTime()), "登记寄回"));

        // ---- 后台 10 维搜索与导出 ----
        JsonNode rows = ok(c.get("/a/donate/items?bizId=" + campaign + "&exclusiveCode=" + code.toLowerCase()
                + "&page=1&size=10", admin, "物资搜索")).data().get("records");
        assertEquals(1, rows.size());
        assertEquals("已送达", rows.get(0).get("statusLabel").asText());
        HttpResponse<byte[]> xlsx = raw("/a/donate/items/export?bizId=" + campaign, admin);
        assertEquals(200, xlsx.statusCode());
        assertTrue(xlsx.headers().firstValue("Content-Type").orElse("").contains("spreadsheetml"),
                "导出是 xlsx 下载，不是 JSON");
        assertTrue(xlsx.body().length > 1000);

        // ---- 本次活动数据 ----
        JsonNode stats = ok(c.get("/v/donate/book-campaigns/" + campaign, donor, "活动详情")).data().get("stats");
        assertEquals(1, stats.get("participants").asInt());
        assertEquals(2, stats.get("books").asInt(), "小王子 ×2 合格");
        assertEquals(0, stats.get("sports").asInt(), "旧球拍不合格，不算「收到」");

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());

        // ---- 负向：跨人、跨角色 ----
        ApiStressClient neg = new ApiStressClient(port);
        String stranger = neg.devLogin("bf-stranger-" + tag);
        ApiStressClient.Resp other = neg.get("/v/donate/shipments/" + shipmentId, stranger, "看别人的运单");
        assertEquals(400, other.code());
        assertEquals("运单不存在", other.message(), "不是本人的与不存在同一句话");
        ApiStressClient.Resp crossRole = neg.post("/a/donate/shipments/" + shipmentId + "/arrive", stranger, null,
                "志愿者 token 调后台");
        assertNotEquals(200, crossRole.code(), "志愿者 token 过不了 /a/**");
        ApiStressClient.Resp noLogin = neg.get("/v/donate/book-campaigns", null, "未登录");
        assertNotEquals(200, noLogin.code());
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "HTTP 调用");
        return r;
    }

    private static JsonNode find(JsonNode records, String name) {
        for (JsonNode n : records) {
            if (name.equals(n.get("name").asText())) {
                return n;
            }
        }
        throw new AssertionError("记录里没有 " + name + "：" + records);
    }

    private HttpResponse<byte[]> raw(String path, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", token)
                .GET().build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofByteArray());
    }
}
