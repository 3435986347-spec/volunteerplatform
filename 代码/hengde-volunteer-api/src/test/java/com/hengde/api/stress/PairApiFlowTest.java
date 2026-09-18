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

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 助学助困结对的 <b>HTTP 全流程</b>（V3 结对批）——<b>必跑</b>，不打 stress 标签。
 *
 * <p>理由同 {@code BookDonationApiFlowTest} / {@code WishApiFlowTest}：服务层用例证明规则对，
 * 这一条证明<b>接口真的接得上</b>。本批还多一件要证的事——<b>跨模块的出证链路</b>：
 * donate 确认结对 → 领域事件 → honor 出证 → 志愿者在「我的证书」里看得到。</p>
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
class PairApiFlowTest {

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void registerEstablishAndCertificateOverHttp() {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        // ---- 后台：建项目 → 上架 ----
        String title = "接口结对-" + tag;
        long projectId = ok(c.post("/a/donate/pair-projects", admin, Map.of(
                "title", title, "projectType", 1, "detail", "某同学一年的学费",
                "targetAmount", 1000), "建结对项目")).dataAsLong();
        JsonNode draft = ok(c.get("/a/donate/pair-projects/" + projectId, admin, "后台详情")).data();
        assertEquals(0, draft.get("status").asInt(), "新建落草稿");
        ok(c.post("/a/donate/pair-projects/" + projectId + "/publish", admin, null, "上架"));

        // ---- 志愿者：看项目 → 登记（全款＝按缺口算） ----
        String donor = c.devLogin("pf-" + tag);
        long donorId = volunteerId("pf-" + tag);
        JsonNode list = ok(c.get("/v/donate/pair-projects?tab=1&page=1&size=20", donor, "结对项目列表"))
                .data().get("records");
        assertTrue(list.toString().contains(title), "「结对助学」页签里应有它");
        JsonNode detail = ok(c.get("/v/donate/pair-projects/" + projectId, donor, "项目详情")).data();
        assertTrue(detail.get("canRegister").asBoolean());
        assertEquals(0, detail.get("progressPercent").asInt());

        JsonNode registered = ok(c.post("/v/donate/pair-projects/" + projectId + "/pairs", donor,
                Map.of("amountType", 2, "remark", "我来结这个对"), "结对登记")).data();
        String pairId = registered.get("id").asText();
        assertTrue(registered.get("id").isTextual(), "Long 一律序列化成字符串");
        // ⚠️ 金额别按字符串比：客户端用默认 ObjectMapper 解析，JSON 里的 1000.00 会变成 double 1000.0，
        // asText() 于是给出 "1000.0"。服务端出参是对的，按数值比才不会被这层转换骗到
        assertEquals(0, money(registered, "amount").compareTo(new BigDecimal("1000")), "全款＝按项目当前缺口算");
        assertEquals(0, registered.get("status").asInt(), "登记后待协会确认");

        // ---- 后台确认成立 → 认捐额、项目状态、证书 ----
        ok(c.post("/a/donate/pairs/" + pairId + "/establish", admin, null, "确认结对成立"));
        JsonNode paired = ok(c.get("/a/donate/pair-projects/" + projectId, admin, "确认后的项目")).data();
        assertEquals(2, paired.get("status").asInt(), "认捐额达标 → 已结对");
        assertEquals(100, paired.get("progressPercent").asInt());
        assertEquals(1, paired.get("participantCount").asInt());

        JsonNode certs = ok(c.get("/v/honor/certificates?page=1&size=10", donor, "我的证书")).data().get("records");
        JsonNode donationCert = null;
        for (JsonNode n : certs) {
            if (n.get("type").asInt() == 3) {
                donationCert = n;
            }
        }
        assertTrue(donationCert != null, "结对成立应自动出一张捐赠证书（跨模块事件链路）：" + certs);
        assertEquals("pair:" + pairId, donationCert.get("bizRef").asText());
        assertEquals(title, donationCert.get("sourceTitle").asText(), "来源写的是结对项目名");
        assertEquals(false, donationCert.get("fileReady").asBoolean(), "PDF 懒渲染：这时还没生成");

        // ---- 来信：公开信人人可见，私信只给收信人 ----
        ok(c.post("/a/donate/pair-projects/" + projectId + "/letters", admin,
                Map.of("content", "谢谢大家的帮助"), "录入公开信"));
        ok(c.post("/a/donate/pair-projects/" + projectId + "/letters", admin,
                Map.of("pairRecordId", pairId, "content", "谢谢您资助我读书"), "录入私信"));
        JsonNode mineLetters = ok(c.get("/v/donate/pair-projects/" + projectId + "/letters?page=1&size=10",
                donor, "我看到的来信")).data().get("records");
        assertEquals(2, mineLetters.size());

        String strangerKey = "ps-" + tag;
        String stranger = neg.devLogin(strangerKey);
        JsonNode otherLetters = ok(neg.get("/v/donate/pair-projects/" + projectId + "/letters?page=1&size=10",
                stranger, "旁人看到的来信")).data().get("records");
        assertEquals(1, otherLetters.size(), "私信不该下发给别人");
        assertEquals("谢谢大家的帮助", otherLetters.get(0).get("content").asText());

        // ---- 我的结对；众筹项目（本批无支付，已筹恒为 0） ----
        JsonNode myPairs = ok(c.get("/v/donate/pairs/mine?page=1&size=10", donor, "我的结对")).data().get("records");
        assertEquals(1, myPairs.get(0).get("status").asInt(), "已成立");
        assertEquals(title, myPairs.get(0).get("projectTitle").asText());
        long crowdId = ok(c.post("/a/donate/crowdfunds", admin, Map.of(
                "title", "接口众筹-" + tag, "targetAmount", 20000), "建众筹")).dataAsLong();
        ok(c.post("/a/donate/crowdfunds/" + crowdId + "/publish", admin, null, "上架众筹"));
        JsonNode crowd = ok(c.get("/v/donate/crowdfunds/" + crowdId, donor, "众筹详情")).data();
        assertEquals(0, money(crowd, "raisedAmount").compareTo(BigDecimal.ZERO));
        assertEquals(0, crowd.get("donorCount").asInt());

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());

        // ---- 取消已成立的结对：认捐额退回、项目回到进行中、证书被撤销 ----
        ok(c.post("/a/donate/pairs/" + pairId + "/cancel", admin, Map.of("reason", "结对人反悔"), "取消结对"));
        JsonNode reopened = ok(c.get("/a/donate/pair-projects/" + projectId, admin, "取消后的项目")).data();
        assertEquals(1, reopened.get("status").asInt(), "退回认捐额后回到进行中");
        assertEquals(0, money(reopened, "pledgedAmount").compareTo(BigDecimal.ZERO));
        JsonNode afterCerts = ok(c.get("/v/honor/certificates?page=1&size=10", donor, "取消后的我的证书"))
                .data().get("records");
        for (JsonNode n : afterCerts) {
            assertNotEquals(3, n.get("type").asInt(), "结对取消了，那张捐赠证书不该还留着：" + afterCerts);
        }

        // ---- 负向：志愿者 token 过不了 /a；旁人撤不了别人的登记 ----
        ApiStressClient.Resp crossRole = neg.get("/a/donate/pair-projects", stranger, "志愿者 token 调后台");
        assertNotEquals(200, crossRole.code());
        ApiStressClient.Resp withdrawOther = neg.delete("/v/donate/pair-projects/" + projectId + "/pairs",
                stranger, "撤别人的登记");
        assertEquals(400, withdrawOther.code());
        assertTrue(withdrawOther.message().contains("没有登记"), withdrawOther.message());
        assertTrue(donorId > 0);
    }

    /** 出参里的金额：先取文本再转 BigDecimal，绕开 JsonNode 把 1000.00 读成 double 的那层。 */
    private static BigDecimal money(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "HTTP 调用");
        return r;
    }

    private long volunteerId(String devKey) {
        return jdbc.queryForObject("SELECT id FROM volunteer WHERE openid = ?", Long.class, "dev:" + devKey);
    }
}
