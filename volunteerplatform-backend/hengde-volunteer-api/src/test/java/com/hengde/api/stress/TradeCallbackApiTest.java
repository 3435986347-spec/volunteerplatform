package com.hengde.api.stress;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信支付 webhook 的<b>可达性</b>（V3 trade 批）——<b>必跑</b>。
 *
 * <p><b>这条用例钉的是一件很容易被无声破坏的事</b>：{@code /callback/**} 必须<b>免登录可达</b>。
 * 文档里一直写着「Sa-Token 只 match /v /a /e，所以回调天然不进鉴权链」，
 * 而实际代码里拦截器是注册在 {@code /**} 上的，只是处理器内部只对那三段做 {@code check}。
 * 也就是说，<b>哪天有人往处理器里加一条全局规则，两条 webhook 会当场变成 401 且没有任何征兆</b>——
 * 微信那边只会看到「通知失败」，而钱已经收了。所以这件事必须由用例来保证，不能靠那段描述。</p>
 *
 * <p>用例<b>不关心业务结果</b>：测试环境没有商户资质，验签必然失败、回 FAIL。
 * 它只区分两件事——<b>被鉴权挡住（401/403）</b> 还是 <b>真的走到了控制器</b>。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(classes = HengdeVolunteerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class TradeCallbackApiTest {

    @Value("${local.server.port}")
    private int port;

    @Test
    void callbackIsReachableWithoutLogin_whileAdminEndpointsAreNot() throws Exception {
        HttpResponse<String> pay = post("/callback/trade/wechat/pay", "{}");
        assertNotEquals(401, pay.statusCode(), "回调被鉴权挡住了——微信永远收不到 SUCCESS，而钱已经收了：" + pay.body());
        assertNotEquals(403, pay.statusCode(), pay.body());
        assertTrue(pay.body() != null && pay.body().startsWith("{\"code\":\"FAIL\""),
                "没有商户资质，验签必然失败、回 FAIL——能看到 FAIL 恰恰证明请求走到了控制器；"
                        + "且报文必须是 JSON 对象，不是被 Jackson 再序列化一遍的 JSON 字符串：" + pay.body());
        assertTrue(pay.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), pay.body());

        HttpResponse<String> refund = post("/callback/trade/wechat/refund", "{}");
        assertNotEquals(401, refund.statusCode(), refund.body());
        assertNotEquals(403, refund.statusCode(), refund.body());

        // 反向确认：同一个客户端、同样不带 token 打管理端，必须被挡下。
        // 没有这一条，上面两条在「鉴权整个失效」时也会绿——那就成了一对误绿的用例。
        // ⚠️ 这一条顺带钉住 SaTokenConfigure 启动时注册管理端 StpLogic 那一行：本类里没有任何人登录过管理端，
        // 缺那一行时注解校验找不到 type=admin 的 StpLogic，这里拿到的是 500 不是 401（首次就是这么红的）。
        // 只在本类单独跑、JVM 里此前没人碰过 StpAdminUtil 时才确定性地红——同 JVM 先跑过登录的用例会把它掩盖
        HttpResponse<String> admin = post("/a/trade/orders/1/close", "{}");
        assertEquals(401, admin.statusCode(), "不带 token 的 /a/** 必须 401：" + admin.body());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Wechatpay-Serial", "serial")
                .header("Wechatpay-Signature", "signature")
                .header("Wechatpay-Timestamp", "1700000000")
                .header("Wechatpay-Nonce", "nonce")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }
}
