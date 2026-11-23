package com.hengde.api.stress;

import cn.hutool.core.util.CreditCodeUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.hengde.api.HengdeVolunteerApplication;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 爱心企业赞助商品与积分的 <b>HTTP 全流程</b>（V4 爱心企业批·商品段）——<b>必跑</b>。
 *
 * <p>后台建企业 → 企业登录发商品提交 → 后台审核上架 → 志愿者在企业主页看到并兑换 → 后台审核核销 → 补记入账 →
 * 企业看到兑换单（不带取货码）与积分余额 → 后台扣减 → 暂停企业：志愿者商城里看不到、企业端被挡。</p>
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
class EnterpriseSponsorApiFlowTest {

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;

    @Test
    void enterpriseGoodsExchangeCreditAndPause() {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String root = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);
        LocalDateTime since = LocalDateTime.now().minusMinutes(5).withNano(0);

        Map<String, Object> ent = new HashMap<>();
        ent.put("name", "赞助商行-" + tag);
        ent.put("creditCode", CreditCodeUtil.randomCreditCode());
        ent.put("leaderName", "负责人");
        ent.put("leaderPhone", "157" + String.format("%08d", System.nanoTime() % 100_000_000L));
        ent.put("username", "sp_" + tag);
        ent.put("password", "pass1234");
        long entId = ok(c.post("/a/enterprise/enterprises", root, ent, "后台建企业")).dataAsLong();
        String et = ok(c.post("/e/auth/login", null, Map.of("username", "sp_" + tag, "password", "pass1234"), "企业登录")).data().get("token").asText();

        // ---- 企业发商品，后台审核上架 ----
        Map<String, Object> goods = Map.of("name", "赞助保温杯-" + tag, "detail", "企业赞助",
                "specs", List.of(Map.of("name", "标准", "points", 12, "stock", 10)));
        long goodsId = ok(c.post("/e/donate/goods", et, goods, "企业新增商品")).dataAsLong();
        ok(c.post("/e/donate/goods/" + goodsId + "/submit", et, null, "企业提交审核"));
        ok(c.post("/a/donate/goods/" + goodsId + "/approve", root, null, "后台审核上架"));

        // ---- 志愿者在企业主页看到并兑换 ----
        String vol = c.devLogin("sponsor-" + tag);
        long volId = Long.parseLong(ok(c.get("/v/user/profile", vol, "我的资料")).data().get("no").asText());
        JsonNode homepage = ok(c.get("/v/enterprise/enterprises/" + entId + "/goods?page=1&size=20", vol, "企业主页赞助商品")).data().get("records");
        assertTrue(homepage.toString().contains("赞助保温杯-" + tag), homepage.toString());
        long specId = homepage.get(0).get("specs").get(0).get("id").asLong();
        ok(c.post("/a/activity/points/adjust", root, Map.of("volunteerId", volId, "changeAmount", 100, "reason", "用例预置",
                "requestId", "sp-" + tag), "给志愿者积分"));
        long orderId = ok(c.post("/v/donate/orders", vol, Map.of("specId", specId), "兑换")).data().get("id").asLong();
        ok(c.post("/a/donate/orders/" + orderId + "/approve", root, null, "后台审核兑换单"));
        String code = ok(c.get("/v/donate/orders/" + orderId, vol, "看取货码")).data().get("pickupCode").asText();
        ok(c.post("/a/donate/orders/verify", root, Map.of("code", code), "后台核销"));

        // ---- 补记入账，企业看兑换单与余额 ----
        String sinceText = since.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).replace(" ", "%20");
        assertTrue(ok(c.post("/a/enterprise/points/reconcile?since=" + sinceText, root, null, "补记")).dataAsLong() >= 1);
        JsonNode orders = ok(c.get("/e/donate/orders?page=1&size=20", et, "企业看兑换单")).data().get("records");
        assertEquals(1, orders.size());
        assertFalse(orders.toString().contains(code), "企业端不下发取货码：" + orders);
        assertEquals(12, ok(c.get("/e/enterprise/points", et, "企业积分")).data().get("balance").asInt());
        assertNotEquals(200, neg.post("/a/enterprise/enterprises/" + entId + "/points/adjust", et,
                Map.of("amount", -1, "remark", "企业自己扣", "requestId", "self-" + tag), "企业 token 调后台调整").code());
        assertEquals(2, ok(c.post("/a/enterprise/enterprises/" + entId + "/points/adjust", root,
                Map.of("amount", -10, "remark", "兑换广告位", "requestId", "ad-" + tag), "后台扣减")).data().get("balance").asInt());

        // ---- 暂停企业：志愿者商城里没了，企业端被挡 ----
        ok(c.post("/a/enterprise/enterprises/" + entId + "/pause", root, Map.of("reason", "用例暂停"), "暂停企业"));
        JsonNode mall = ok(c.get("/v/donate/goods?page=1&size=100", vol, "志愿者商城")).data().get("records");
        assertFalse(mall.toString().contains("赞助保温杯-" + tag), "暂停的企业商品不在商城里");
        assertNotEquals(200, neg.post("/v/donate/orders", vol, Map.of("specId", specId), "暂停后兑换").code());
        assertNotEquals(200, neg.get("/e/donate/goods", et, "暂停后企业端").code());

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "HTTP 调用");
        return r;
    }
}
