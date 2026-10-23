package com.hengde.api.stress;

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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发起小组扣 50 积分的 <b>HTTP 全流程</b>（原型 P23，需方 2026-10-23 拍板）——<b>必跑</b>。
 *
 * <p>扣分经 organization 的 {@code GroupCreationFee} 接口由 activity 实现；organization 自己的测试上下文里没有实现、
 * 那时不扣分——所以只有在装配了全部模块的 api 里才证明得了「部署出来的包真的会扣」。</p>
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
class GroupCreationFeeApiFlowTest {

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void creatingAGroupCosts50_notEnoughIsRefused_andRejectionRefunds() {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        String rich = c.devLogin("gf-rich-" + tag);
        long richId = jdbc.queryForObject("SELECT id FROM volunteer WHERE openid = ?", Long.class, "dev:gf-rich-" + tag);
        ApiStressClient.requireOk(c.post("/a/activity/points/adjust", admin, Map.of("volunteerId", richId,
                "changeAmount", 60, "reason", "发起小组用例预置", "requestId", "gf-" + tag), "预置积分"), "预置积分");

        long groupId = ok(c.post("/v/organization/groups", rich, Map.of("name", "扣分小组" + tag, "description", "用例"),
                "发起小组")).dataAsLong();
        assertEquals(10, balance(c, rich), "发起即扣 50");

        String poor = neg.devLogin("gf-poor-" + tag);
        ApiStressClient.Resp refused = neg.post("/v/organization/groups", poor,
                Map.of("name", "没钱小组" + tag, "description", "用例"), "积分不够发起");
        assertNotEquals(200, refused.code());
        assertTrue(refused.message().contains("积分不足"), refused.message());

        ok(c.post("/a/organization/groups/applications/" + groupId + "/reject", admin, Map.of("reason", "用例驳回"), "驳回建组"));
        assertEquals(60, balance(c, rich), "驳回如数退回");
        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());
    }

    private static int balance(ApiStressClient c, String token) {
        return ok(c.get("/v/activity/points", token, "积分总览")).data().get("balance").asInt();
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "HTTP 调用");
        return r;
    }
}
