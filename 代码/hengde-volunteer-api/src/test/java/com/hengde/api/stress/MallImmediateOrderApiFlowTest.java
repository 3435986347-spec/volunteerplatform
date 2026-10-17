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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 积分商城「兑换不再审核 / 领取方式每件商品自己定 / 虚拟商品」的 <b>HTTP 全流程</b>（协会 2026-10-16 答复五 乙-5、丙-3）——<b>必跑</b>。
 *
 * <p>这里显式关掉兑换审核（{@code hengde.donate.mall.order-review=false}，也就是线上的默认值）；
 * 其余 HTTP 用例在 {@code application-test.yaml} 里留在改造前的审核流程上。</p>
 *
 * <p>只有走真实 HTTP 才证明得了的几件：后台上传的兑换二维码地址与保存商品时的「本系统上传」判定对得上；
 * 二维码<b>不出现在公开的商品详情响应里</b>、只出现在本人兑换成功后的兑换详情里；
 * 志愿者取消成功的单被拒、协会取消后积分退回。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(classes = HengdeVolunteerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hengde.auth.dev-login-enabled=true", "hengde.donate.mall.order-review=false"})
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class MallImmediateOrderApiFlowTest {

    /** 1×1 透明 PNG：上传校验看文件头 */
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
    void pointsOnlyExchangeSucceedsAtOnce_onlyTheAssociationCancels_andVirtualQrStaysOffTheGoodsPage() throws Exception {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        // ---- 后台：两个自提点，商品只允许其中一个 ----
        long office = ok(c.post("/a/donate/pickup-points", admin, Map.of("name", "协会办公室-" + tag, "address", "西湖新村 17 号"),
                "新增自提点")).dataAsLong();
        long other = ok(c.post("/a/donate/pickup-points", admin, Map.of("name", "别的点-" + tag, "address", "某路 2 号"),
                "新增自提点")).dataAsLong();
        Map<String, Object> bag = goodsBody("帆布袋-" + tag);
        bag.put("allowPickup", 1);
        bag.put("allowExpress", 1);
        bag.put("pickupPointIds", List.of(office));
        bag.put("expressPoints", 20);
        long[] bagIds = publish(c, admin, bag);

        // 虚拟商品：二维码先走后台上传
        String qr = upload(admin, "goods", "qr.png", PNG);
        Map<String, Object> vip = goodsBody("会员资格-" + tag);
        vip.put("allowPickup", 1);
        vip.put("allowExpress", 0);
        vip.put("virtualGoods", 1);
        vip.put("redeemQrUrl", qr);
        long[] vipIds = publish(c, admin, vip);

        // ---- 志愿者：预置积分 ----
        String key = "mi-" + tag;
        String me = c.devLogin(key);
        long vid = jdbc.queryForObject("SELECT id FROM volunteer WHERE openid = ?", Long.class, "dev:" + key);
        ok(c.post("/a/activity/points/adjust", admin, Map.of("volunteerId", vid, "changeAmount", 300,
                "reason", "兑换用例预置", "requestId", "mi-pt-" + tag), "预置积分"));

        // 商品详情：领取方式按商品自己的设置给
        JsonNode detail = ok(c.get("/v/donate/goods/" + bagIds[0], me, "商品详情")).data();
        assertTrue(detail.get("allowPickup").asBoolean() && detail.get("allowExpress").asBoolean(), detail.toString());
        assertEquals(20, detail.get("expressPoints").asInt());
        assertEquals(1, detail.get("pickupPoints").size(), "只列这件商品允许的自提点：" + detail);
        assertEquals(office, detail.get("pickupPoints").get(0).get("id").asLong());

        // 选了商品不允许的自提点：拒
        assertNotEquals(200, neg.post("/v/donate/orders", me, pickup(bagIds[1], other), "选错自提点").code());

        // ---- 纯积分：下单即成功（待领取），快照所选自提点 ----
        JsonNode order = ok(c.post("/v/donate/orders", me, pickup(bagIds[1], office), "兑换")).data();
        String orderId = order.get("id").asText();
        assertEquals(1, order.get("status").asInt(), "协会 10-16 乙-5：纯积分点了兑换即成功，不再待审核");
        JsonNode mine = ok(c.get("/v/donate/orders/" + orderId, me, "兑换详情")).data();
        assertEquals("协会办公室-" + tag, mine.get("pickupSiteName").asText());
        assertTrue(mine.hasNonNull("pickupCode"), "成功的自提单马上有取货码：" + mine);
        assertEquals(300 - 30, balance(vid));

        // 成功的单志愿者不能自己取消；协会取消后积分退回
        ApiStressClient.Resp self = neg.delete("/v/donate/orders/" + orderId, me, "本人取消");
        assertNotEquals(200, self.code());
        assertTrue(self.message().contains("联系协会"), self.message());
        ok(c.post("/a/donate/orders/" + orderId + "/cancel", admin, Map.of("reason", "库存盘点有误"), "协会取消"));
        assertEquals(4, ok(c.get("/v/donate/orders/" + orderId, me, "取消后")).data().get("status").asInt());
        assertEquals(300, balance(vid), "协会取消即退分");

        // ---- 虚拟商品：商品页没有二维码，兑换成功后本人的兑换详情里有 ----
        JsonNode vipPage = ok(c.get("/v/donate/goods/" + vipIds[0], me, "虚拟商品详情")).data();
        assertTrue(vipPage.get("virtualGoods").asBoolean());
        assertTrue(!vipPage.hasNonNull("redeemQrUrl"), "公开的商品页上挂着兑换二维码，就不必兑换了：" + vipPage);
        JsonNode vipOrder = ok(c.post("/v/donate/orders", me, pickup(vipIds[1], null), "兑换虚拟商品")).data();
        JsonNode vipMine = ok(c.get("/v/donate/orders/" + vipOrder.get("id").asText(), me, "虚拟商品兑换详情")).data();
        assertTrue(vipMine.get("virtualGoods").asBoolean());
        assertEquals(qr, vipMine.get("redeemQrUrl").asText(), "兑换成功后，本人的兑换详情里给兑换二维码");
        String stranger = neg.devLogin("mi-s-" + tag);
        assertNotEquals(200, neg.get("/v/donate/orders/" + vipOrder.get("id").asText(), stranger, "旁人看兑换详情").code());

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());
    }

    // ---------------- helpers ----------------

    private static Map<String, Object> goodsBody(String name) {
        Map<String, Object> spec = new HashMap<>(Map.of("name", "标准", "points", 30, "stock", 10));
        spec.put("cashFen", 0);
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("specs", List.of(spec));
        return m;
    }

    /** @return {商品 id, 规格 id} */
    private static long[] publish(ApiStressClient c, String admin, Map<String, Object> body) {
        long goodsId = ok(c.post("/a/donate/goods", admin, body, "建商品")).dataAsLong();
        ok(c.post("/a/donate/goods/" + goodsId + "/submit", admin, null, "提交审核"));
        ok(c.post("/a/donate/goods/" + goodsId + "/approve", admin, null, "审核通过"));
        long specId = Long.parseLong(ok(c.get("/a/donate/goods/" + goodsId, admin, "商品详情")).data()
                .get("specs").get(0).get("id").asText());
        return new long[]{goodsId, specId};
    }

    private static Map<String, Object> pickup(long specId, Long pickupPointId) {
        Map<String, Object> m = new HashMap<>();
        m.put("specId", specId);
        m.put("deliveryType", 1);
        if (pickupPointId != null) {
            m.put("pickupPointId", pickupPointId);
        }
        return m;
    }

    private String upload(String token, String dir, String filename, byte[] content) throws Exception {
        String boundary = "----mallqr" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"dir\"\r\n\r\n" + dir + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/a/files/upload"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), resp.body());
        JsonNode root = JSON.readTree(resp.body());
        assertEquals(200, root.get("code").asInt(), resp.body());
        return root.get("data").get("url").asText();
    }

    private int balance(long vid) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(change_amount), 0) FROM point_record WHERE volunteer_id = ? "
                + "AND is_deleted = 0", Integer.class, vid);
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "HTTP 调用");
        return r;
    }
}
