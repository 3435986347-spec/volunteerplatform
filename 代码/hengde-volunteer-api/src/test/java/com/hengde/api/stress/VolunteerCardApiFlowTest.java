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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 志愿者证的 <b>HTTP 全流程</b>（V4 志愿者证批）——<b>必跑</b>。
 *
 * <p>服务层用例证明不了的是「核验端点真的免登录」：它登记在 {@code SaTokenConfigure} 的公开清单里，漏登记就是 401，
 * 扫码的人（不一定是本平台用户）打不开。这里不带任何 token 调核验，并断言出参里没有手机号；领证本身仍要登录；重置后旧码失效。</p>
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
class VolunteerCardApiFlowTest {

    private static final String PREFIX = "hengde-volunteer-card:";

    @Value("${local.server.port}")
    private int port;

    @Test
    void issueVerifyWithoutLoginAndReset() {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String tag = Long.toString(System.nanoTime(), 36);

        assertNotEquals(200, neg.get("/v/user/volunteer-card", null, "不登录领证").code());
        String me = c.devLogin("card-" + tag);
        JsonNode card = ok(c.get("/v/user/volunteer-card", me, "我的志愿者证")).data();
        assertEquals("QR_CODE", card.get("qrType").asText(), "测试环境没有小程序 AppID");
        String content = card.get("qrContent").asText();
        assertTrue(content.startsWith(PREFIX), content);
        String token = content.substring(PREFIX.length());

        JsonNode pub = ok(c.get("/v/user/volunteer-cards/verify?token=" + enc(token), null, "免登录核验")).data();
        assertEquals(card.get("no").asText(), pub.get("no").asText());
        assertTrue(pub.get("maskedName").asText().contains("*") || pub.get("maskedName").asText().length() <= 1, pub.toString());
        assertFalse(pub.has("phone") || pub.has("realName") || pub.has("school"), "公开核验不带手机号 / 完整姓名 / 学校：" + pub);

        assertNotEquals(200, neg.get("/v/user/volunteer-cards/verify?token=" + enc(card.get("no").asText()), null, "拿志愿者 id 当令牌").code());
        JsonNode renewed = ok(c.post("/v/user/volunteer-card/reset", me, null, "重置")).data();
        String fresh = renewed.get("qrContent").asText().substring(PREFIX.length());
        assertNotEquals(token, fresh);
        assertNotEquals(200, neg.get("/v/user/volunteer-cards/verify?token=" + enc(token), null, "旧码").code());
        ok(c.get("/v/user/volunteer-cards/verify?token=" + enc(fresh), null, "新码免登录核验"));

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "HTTP 调用");
        return r;
    }
}
