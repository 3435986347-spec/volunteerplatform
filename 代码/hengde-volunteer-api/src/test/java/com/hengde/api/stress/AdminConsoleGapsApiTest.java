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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台控制台接 V3 / V4 页面时补的后端缺口（控制台 V3 / V4 批次）——<b>必跑</b>。
 *
 * <p>控制台是「只能打 /a/** 」的：管理端 token 过不了 /v/**。凡是某个动作在后台有入口、
 * 却只有志愿者端能把要处理的东西列出来的，控制台上就够不着——这里的每一条都是这样撞出来的。</p>
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
class AdminConsoleGapsApiTest {

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 下架评价 DELETE /a/donate/reviews/{id} 早就有，可评价只能在志愿者端列出来——
     * 后台看不到评价，也就无从下架。补 GET /a/donate/goods/{id}/reviews。
     */
    @Test
    void adminCanListGoodsReviews_thenDelistOne() {
        ApiStressClient c = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        long goodsId = ok(c.post("/a/donate/goods", admin, Map.of("name", "评价用例-" + tag,
                "specs", List.of(Map.of("name", "标准", "points", 10, "stock", 5))), "建商品")).dataAsLong();
        ok(c.post("/a/donate/goods/" + goodsId + "/submit", admin, null, "提交"));
        ok(c.post("/a/donate/goods/" + goodsId + "/approve", admin, null, "通过"));
        String specId = ok(c.get("/a/donate/goods/" + goodsId, admin, "详情")).data().get("specs").get(0).get("id").asText();

        String key = "gap-rv-" + tag;
        String donor = c.devLogin(key);
        long vid = jdbc.queryForObject("SELECT id FROM volunteer WHERE openid = ?", Long.class, "dev:" + key);
        ok(c.post("/a/activity/points/adjust", admin, Map.of("volunteerId", vid, "changeAmount", 50,
                "reason", "评价用例预置", "requestId", "gap-rv-" + tag), "预置积分"));
        String orderId = ok(c.post("/v/donate/orders", donor, Map.of("specId", Long.parseLong(specId)), "下单")).data().get("id").asText();
        ok(c.post("/a/donate/orders/" + orderId + "/approve", admin, null, "审核通过"));
        String code = ok(c.get("/v/donate/orders/" + orderId, donor, "取货码")).data().get("pickupCode").asText();
        ok(c.post("/a/donate/orders/verify", admin, Map.of("code", code), "核销"));
        ok(c.post("/v/donate/orders/" + orderId + "/reviews", donor, Map.of("rating", 2, "content", "不当内容"), "评价"));

        JsonNode page = ok(c.get("/a/donate/goods/" + goodsId + "/reviews?page=1&size=10", admin, "后台列评价")).data();
        assertEquals(1, page.get("total").asInt(), page.toString());
        JsonNode review = page.get("records").get(0);
        assertEquals("不当内容", review.get("content").asText());
        assertTrue(review.hasNonNull("volunteerName"), "后台要看得到是谁评的：" + review);

        ok(c.delete("/a/donate/reviews/" + review.get("id").asText(), admin, "下架"));
        assertEquals(0, ok(c.get("/a/donate/goods/" + goodsId + "/reviews?page=1&size=10", admin, "下架后")).data()
                .get("total").asInt(), "下架的评价不再列出");

        // 未登录打这个新端点：401，不是 200
        assertEquals(401, new ApiStressClient(port).get("/a/donate/goods/" + goodsId + "/reviews", null, "未登录").http());
    }

    /**
     * 企业赞助的商品，赞助方名称是企业名称的快照。后台在「商品管理」里编辑时表单里带着赞助方名称一栏，
     * 此前 update 会照单全收——改了就与企业脱钩、清空就没了名字。现在只有平台自营的商品才按表单写；
     * 出参补 sponsorEnterpriseId，控制台据此把这一栏锁住。
     */
    @Test
    void editingSponsoredGoodsKeepsTheEnterpriseNameSnapshot() {
        ApiStressClient c = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        Map<String, Object> ent = new java.util.HashMap<>();
        ent.put("name", "赞助快照企业-" + tag);
        ent.put("creditCode", cn.hutool.core.util.CreditCodeUtil.randomCreditCode());
        ent.put("leaderName", "张三");
        ent.put("leaderPhone", "13900000000");
        ent.put("username", "sp_" + tag);
        ent.put("password", "abc12345");
        long enterpriseId = ok(c.post("/a/enterprise/enterprises", admin, ent, "代建企业")).dataAsLong();

        List<Map<String, Object>> specs = List.of(Map.of("name", "标准", "points", 10, "stock", 5));
        long sponsored = ok(c.post("/a/enterprise/enterprises/" + enterpriseId + "/goods", admin,
                Map.of("name", "赞助商品-" + tag, "specs", specs), "代发布")).dataAsLong();
        long own = ok(c.post("/a/donate/goods", admin,
                Map.of("name", "自营商品-" + tag, "sponsorName", "原赞助方", "specs", specs), "自营")).dataAsLong();

        JsonNode before = ok(c.get("/a/donate/goods/" + sponsored, admin, "详情")).data();
        assertEquals(enterpriseId, before.get("sponsorEnterpriseId").asLong(), "后台要知道这是哪家企业赞助的：" + before);
        assertEquals("赞助快照企业-" + tag, before.get("sponsorName").asText());

        Map<String, Object> edit = new java.util.HashMap<>(Map.of("name", "改名-" + tag, "sponsorName", "冒名企业"));
        ok(c.put("/a/donate/goods/" + sponsored, admin, edit, "改企业商品"));
        JsonNode after = ok(c.get("/a/donate/goods/" + sponsored, admin, "改后")).data();
        assertEquals("改名-" + tag, after.get("name").asText(), "其它字段照改");
        assertEquals("赞助快照企业-" + tag, after.get("sponsorName").asText(), "企业赞助的，名称不随表单改");

        ok(c.put("/a/donate/goods/" + own, admin, edit, "改自营商品"));
        JsonNode ownAfter = ok(c.get("/a/donate/goods/" + own, admin, "自营改后")).data();
        assertEquals("冒名企业", ownAfter.get("sponsorName").asText(), "平台自营的赞助方名称照常按表单写");
        assertTrue(ownAfter.path("sponsorEnterpriseId").isMissingNode() || ownAfter.get("sponsorEnterpriseId").isNull());
    }

    /**
     * 请求本身不对时的状态码。此前三类都落进兜底 → 500「服务器内部错误」+ ERROR 堆栈，
     * 控制台拼错路径时看到「服务器内部错误」，会让人去查服务端而不是查调用方。
     */
    @Test
    void malformedRequestsAreClientErrorsNot500() {
        ApiStressClient c = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);

        ApiStressClient.Resp missingPath = c.get("/a/activity/points/summary", admin, "不存在的路径");
        assertEquals(404, missingPath.http(), missingPath.message());
        assertEquals(404, missingPath.code());

        ApiStressClient.Resp wrongMethod = c.call("DELETE", "/a/donate/orders/verify", admin, null, "方法不对");
        assertEquals(405, wrongMethod.http(), wrongMethod.message());
        assertEquals(405, wrongMethod.code());

        ApiStressClient.Resp missingParam = c.call("PATCH", "/a/donate/coupons/1/status", admin, null, "缺必填参数");
        assertEquals(400, missingParam.http(), missingParam.message());
        assertTrue(missingParam.message().contains("status"), "要说出缺的是哪个参数：" + missingParam.message());
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "");
        return r;
    }
}
