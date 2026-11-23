package com.hengde.api.stress;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 积分商城·快递寄送的 <b>HTTP 全流程</b>（V3 商城快递批）——<b>必跑</b>。
 *
 * <p>这条走的是<b>真实部署形态</b>：微信支付用的是真实现、<b>默认关闭</b>（没有商户资质）。所以它恰好钉住两件
 * 服务层用例（假渠道开着）证明不了的事：</p>
 * <ol>
 *   <li><b>积分抵扣快递费那条路不需要商户资质</b>，现在就能从下单一路走到签收；</li>
 *   <li><b>需要现金的单在下单那一刻就被拒</b>，不占库存、不扣分——而不是落一张付不了的待支付单等超时。</li>
 * </ol>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(classes = HengdeVolunteerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hengde.auth.dev-login-enabled=true",
                "hengde.donate.mall.express.enabled=true",
                "hengde.donate.mall.express.fee-fen=800",
                "hengde.donate.mall.express.points-per-yuan=10"})
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class MallExpressApiFlowTest {

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void expressWithPointsShippingEndToEnd_andCashIsRefusedWithoutMerchant() {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        // ---- 后台：一件纯积分、一件积分 + 现金 ----
        long pureSpec = goods(c, admin, "快递帆布袋-" + tag, 0)[1];
        long[] cash = goods(c, admin, "积分加现金水杯-" + tag, 500);
        long cashSpec = cash[1];
        JsonNode cashGoods = ok(c.get("/v/donate/goods/" + cash[0], c.devLogin("mx-peek-" + tag), "商品详情")).data();
        assertEquals("5.00", cashGoods.get("specs").get(0).get("cashYuan").asText(), "现金部分给元，前端不必自己除以 100");

        // ---- 志愿者：预置积分 ----
        String key = "mx-" + tag;
        String donor = c.devLogin(key);
        long vid = jdbc.queryForObject("SELECT id FROM volunteer WHERE openid = ?", Long.class, "dev:" + key);
        ok(c.post("/a/activity/points/adjust", admin, Map.of("volunteerId", vid, "changeAmount", 500,
                "reason", "快递用例预置", "requestId", "mx-pt-" + tag), "预置积分"));

        // ---- 需要现金的单：下单即被拒，什么都不占 ----
        ApiStressClient.Resp cashResp = neg.post("/v/donate/orders", donor, place(cashSpec, 1, null), "现金商品下单");
        assertNotEquals(200, cashResp.code(), "没有商户资质，需要现金的单必须当场被拒");
        assertTrue(cashResp.message().contains("微信支付尚未开通"), cashResp.message());
        ApiStressClient.Resp cashShipping = neg.post("/v/donate/orders", donor, place(pureSpec, 2, 1), "现金付快递费");
        assertNotEquals(200, cashShipping.code());
        assertTrue(cashShipping.message().contains("积分抵扣"), "要告诉他换成积分抵扣：" + cashShipping.message());
        assertEquals(500, balance(vid), "被拒的单不扣分");

        // ---- 积分抵扣快递费：下单 → 审核（待发货）→ 发货 → 签收 ----
        JsonNode order = ok(c.post("/v/donate/orders", donor, place(pureSpec, 2, 2), "快递积分下单")).data();
        String orderId = order.get("id").asText();
        assertEquals(0, order.get("status").asInt(), "不需要付现金，直接待审核");
        assertEquals(30 + 80, order.get("points").asInt(), "快递费 8 元 × 10 积分/元 = 80 积分，并进实际扣分");
        assertEquals("13800000000", order.get("recvPhone").asText(), "本人看到明文电话");
        assertEquals(500 - order.get("points").asInt(), balance(vid));

        ok(c.post("/a/donate/orders/" + orderId + "/approve", admin, null, "审核通过"));
        JsonNode approved = ok(c.get("/v/donate/orders/" + orderId, donor, "审核后详情")).data();
        assertEquals("待发货", approved.get("statusLabel").asText());
        assertTrue(approved.path("pickupCode").isMissingNode() || approved.get("pickupCode").isNull(), "快递单没有取货码");

        ok(c.post("/a/donate/orders/" + orderId + "/ship", admin,
                Map.of("expressCode", "shunfeng", "expressNo", "sf 998 877"), "登记发货"));
        JsonNode shipped = ok(c.get("/v/donate/orders/" + orderId, donor, "发货后详情")).data();
        assertEquals("已发货", shipped.get("statusLabel").asText());
        assertEquals("SF998877", shipped.get("expressNo").asText());

        // 旁人不能替他确认收货；他本人可以
        String stranger = neg.devLogin("mx-s-" + tag);
        assertNotEquals(200, neg.post("/v/donate/orders/" + orderId + "/receive", stranger, null, "旁人确认收货").code());
        ok(c.post("/v/donate/orders/" + orderId + "/receive", donor, null, "确认收货"));
        assertEquals("已签收", ok(c.get("/v/donate/orders/" + orderId, donor, "签收后详情")).data()
                .get("statusLabel").asText());

        // 签收后能评价（资格闸门沿用「须已领取」）
        ok(c.post("/v/donate/orders/" + orderId + "/reviews", donor, Map.of("rating", 5, "content", "收到了"), "评价"));

        // ---- 自提单带收件地址：报错而不是静默清空 ----
        Map<String, Object> wrong = place(pureSpec, 1, null);
        wrong.put("recvAddress", "某路 1 号");
        ApiStressClient.Resp wrongResp = neg.post("/v/donate/orders", donor, wrong, "自提带地址");
        assertNotEquals(200, wrongResp.code());
        assertFalse(wrongResp.message().isBlank());

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());
    }

    /** @return {商品 id, 规格 id} */
    private static long[] goods(ApiStressClient c, String admin, String name, int cashFen) {
        Map<String, Object> spec = new HashMap<>(Map.of("name", "标准", "points", 30, "stock", 10));
        spec.put("cashFen", cashFen);
        long goodsId = ok(c.post("/a/donate/goods", admin, Map.of("name", name, "specs", List.of(spec)),
                "建商品")).dataAsLong();
        ok(c.post("/a/donate/goods/" + goodsId + "/submit", admin, null, "提交审核"));
        ok(c.post("/a/donate/goods/" + goodsId + "/approve", admin, null, "审核通过"));
        long specId = Long.parseLong(ok(c.get("/a/donate/goods/" + goodsId, admin, "商品详情")).data()
                .get("specs").get(0).get("id").asText());
        return new long[]{goodsId, specId};
    }

    private static Map<String, Object> place(long specId, int deliveryType, Integer shippingPayType) {
        Map<String, Object> m = new HashMap<>();
        m.put("specId", specId);
        m.put("deliveryType", deliveryType);
        if (deliveryType == 2) {
            m.put("shippingPayType", shippingPayType);
            m.put("recvName", "收件人");
            m.put("recvPhone", "13800000000");
            m.put("recvAddress", "广东省雷州市某路 1 号");
        }
        return m;
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
