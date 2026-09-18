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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 捐款的 <b>HTTP 接线</b>（V3 捐款批）——<b>必跑</b>。
 *
 * <p>走真实部署形态：微信支付<b>未开通</b>。所以这条证明的是另一半——接口都注册上了、鉴权对、
 * <b>在线捐款在没有商户资质时被明确拒绝且什么都不落</b>（而不是落一笔付不了的待支付），
 * 以及项目详情里的「已到账 / 捐款人数」口径已经接上。付款成功之后的那一半由 donate 模块的服务层用例（假渠道）承担。</p>
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
class DonationApiFlowTest {

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;

    @Test
    void donationEndpointsAreWired_andOnlineDonationIsRefusedWithoutMerchant() {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        long crowdId = ok(c.post("/a/donate/crowdfunds", admin, Map.of(
                "title", "接口捐款众筹-" + tag, "targetAmount", 5000), "建众筹")).dataAsLong();
        ok(c.post("/a/donate/crowdfunds/" + crowdId + "/publish", admin, null, "上架众筹"));
        long projectId = ok(c.post("/a/donate/pair-projects", admin, Map.of(
                "title", "接口捐款结对-" + tag, "projectType", 1, "targetAmount", 1000), "建结对项目")).dataAsLong();
        ok(c.post("/a/donate/pair-projects/" + projectId + "/publish", admin, null, "上架结对项目"));

        String donor = c.devLogin("dn-" + tag);
        long pairRecordId = ok(c.post("/v/donate/pair-projects/" + projectId + "/pairs", donor,
                Map.of("amountType", 1, "amount", 200), "登记结对")).data().get("id").asLong();

        // ---- 在线捐款：没有商户资质时明确拒绝 ----
        ApiStressClient.Resp crowd = neg.post("/v/donate/crowdfunds/" + crowdId + "/donations", donor,
                Map.of("amount", 20, "code", "wx-code"), "众筹捐款");
        assertNotEquals(200, crowd.code());
        assertTrue(crowd.message().contains("微信支付尚未开通"), crowd.message());
        ApiStressClient.Resp pair = neg.post("/v/donate/pair-projects/" + projectId + "/donations", donor,
                Map.of("code", "wx-code"), "结对捐款");
        assertNotEquals(200, pair.code());
        assertTrue(pair.message().contains("微信支付尚未开通"), pair.message());

        // ---- 公开记录、项目口径、后台列表都接上了 ----
        JsonNode records = ok(c.get("/v/donate/crowdfunds/" + crowdId + "/donations?page=1&size=10", donor, "众筹捐赠记录"))
                .data().get("records");
        assertEquals(0, records.size(), "被拒的捐款不落库，公开记录里自然没有");
        JsonNode detail = ok(c.get("/v/donate/crowdfunds/" + crowdId, donor, "众筹详情")).data();
        assertEquals(0, detail.get("donorCount").asInt());
        JsonNode project = ok(c.get("/v/donate/pair-projects/" + projectId, donor, "结对项目详情")).data();
        assertTrue(project.has("raisedAmount"), "结对项目详情要带「已到账」：" + project);
        assertTrue(project.get("myPair").has("paidAmount"), "我的结对要带「已付」：" + project);
        ok(c.get("/v/donate/pair-projects/" + projectId + "/donations?page=1&size=10", donor, "结对捐赠记录"));
        JsonNode adminList = ok(c.get("/a/donate/donations?page=1&size=10&projectId=" + crowdId, admin, "后台捐款列表"))
                .data().get("records");
        assertEquals(0, adminList.size());

        // ---- 众筹捐物（Row 16）：不经支付，HTTP 全程走得通 ----
        long goodsCf = ok(c.post("/a/donate/crowdfunds", admin, Map.of(
                "title", "接口捐物众筹-" + tag, "targetAmount", 5000, "acceptMoney", false, "acceptGoods", true,
                "goodsNeeded", "书包、文具", "recvName", "协会物资组", "recvPhone", "0759-8888888",
                "recvAddress", "雷州市某路 1 号"), "建只收物的众筹")).dataAsLong();
        ok(c.post("/a/donate/crowdfunds/" + goodsCf + "/publish", admin, null, "上架捐物众筹"));
        JsonNode goodsDetail = ok(c.get("/v/donate/crowdfunds/" + goodsCf, donor, "捐物众筹详情")).data();
        assertTrue(goodsDetail.get("acceptGoods").asBoolean(), goodsDetail.toString());
        assertFalse(goodsDetail.get("acceptMoney").asBoolean(), goodsDetail.toString());
        assertEquals("0759-8888888", goodsDetail.get("recvPhone").asText(), "捐物的人要照着寄：收件信息公开下发");
        String goodsExpressNo = "SF" + System.nanoTime();
        JsonNode goodsShipment = ok(c.post("/v/donate/crowdfunds/" + goodsCf + "/goods-donations", donor, Map.of(
                "expressCode", "shunfeng", "expressNo", goodsExpressNo,
                "items", List.of(Map.of("name", "书包", "itemType", 2, "quantity", 3))), "登记捐物")).data();
        assertEquals(3, goodsShipment.get("bizType").asInt());
        assertEquals(goodsCf, goodsShipment.get("bizId").asLong());
        JsonNode goodsShipments = ok(c.get("/a/donate/shipments?page=1&size=10&bizType=3&bizId=" + goodsCf, admin,
                "后台按众筹项目看运单")).data().get("records");
        assertEquals(1, goodsShipments.size());
        assertTrue(goodsShipments.get(0).get("campaignTitle").asText().startsWith("项目众筹 · "),
                goodsShipments.toString());
        ok(c.get("/a/donate/scan?code=" + goodsExpressNo.toLowerCase(), admin, "扫众筹捐物的快递单号"));
        // ---- 收尾批：我的捐赠记录（捐物那一条在里面）、结对中心详情、后台捐赠数据汇总 ----
        JsonNode mine = ok(c.get("/v/donate/donations/mine?kind=0&page=1&size=10", donor, "我的捐赠记录")).data();
        assertEquals(1, mine.get("total").asInt(), "被拒的捐款没落库，只有那一次捐物：" + mine);
        assertEquals(2, mine.get("records").get(0).get("kind").asInt());
        assertEquals(goodsShipment.get("id").asLong(), mine.get("records").get(0).get("refId").asLong());
        JsonNode center = ok(c.get("/v/donate/pairs/mine/" + pairRecordId, donor, "结对中心详情")).data();
        assertEquals(0, center.get("donations").size());
        assertEquals(0, new java.math.BigDecimal("200").compareTo(new java.math.BigDecimal(center.get("remainingAmount").asText())));
        assertNotEquals(200, neg.get("/v/donate/pairs/mine/" + pairRecordId, c.devLogin("other-" + tag),
                "看别人的结对").code());
        JsonNode summary = ok(c.get("/a/data/donation-summary", admin, "捐赠数据汇总")).data();
        assertTrue(summary.get("pair").get("published").asLong() >= 1, summary.toString());
        JsonNode bookHouses = summary.get("book").get("bookHouses");
        assertTrue(bookHouses == null || bookHouses.isNull(), "书屋数给 null（出参省略 null 字段，前端拿到的是没有这个键）：" + summary);
        assertNotEquals(200, neg.get("/a/data/donation-summary", donor, "志愿者 token 调后台汇总").code());

        assertTrue(neg.post("/v/donate/crowdfunds/" + crowdId + "/goods-donations", donor, Map.of(
                "expressCode", "shunfeng", "expressNo", "SF" + System.nanoTime(),
                "items", List.of(Map.of("name", "书包", "itemType", 2, "quantity", 1))), "给只收钱的项目寄物")
                .message().contains("不接受捐物"));

        // ---- 负向：不存在的捐款、志愿者 token 调后台 ----
        assertNotEquals(200, neg.post("/a/donate/donations/999999999/refund", admin,
                Map.of("reason", "测试"), "退不存在的捐款").code());
        assertNotEquals(200, neg.post("/a/donate/donations/999999999/invoice", admin,
                Map.of("invoiceNo", "FP1"), "给不存在的捐款开票").code());
        assertNotEquals(200, neg.get("/a/donate/donations", donor, "志愿者 token 调后台").code());
        assertNotEquals(200, neg.get("/v/donate/donations/999999999", donor, "看不存在的捐款").code());

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "HTTP 调用");
        return r;
    }
}
