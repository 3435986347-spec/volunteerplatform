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
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快递100 推送 webhook 的<b>HTTP 全链路</b>（V3 物流推送批）——<b>必跑</b>。
 *
 * <p>钉两件服务层用例证明不了的事：</p>
 * <ol>
 *   <li><b>免登录可达</b>：{@code /callback/**} 靠的是 Sa-Token 处理器里「落不到 check 上」，不是白名单
 *       （见 {@code TradeCallbackApiTest}）；</li>
 *   <li><b>表单解码后的 param 就是签名那一串</b>：快递100 推的是 {@code application/x-www-form-urlencoded}，
 *       轨迹文字里有中文、空格、加号——编码或解码任何一侧差一个字符，验签就永远过不了，而服务层用例是直接传字符串的，看不出来。</li>
 * </ol>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(classes = HengdeVolunteerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class LogisticsCallbackApiTest {

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void signedPushIsAcceptedOverHttp_badSignatureIsRejected_noLoginNeeded() throws Exception {
        String no = "SF" + System.nanoTime();
        String salt = "salt-" + System.nanoTime();
        long sid = insertSubscribedShipment(no, salt);
        // 轨迹文字里故意带空格、加号、& 与中文：表单编码里它们最容易出错
        String param = "{\"status\":\"polling\",\"message\":\"\",\"lastResult\":{\"message\":\"ok\",\"nu\":\"" + no
                + "\",\"com\":\"shunfeng\",\"status\":\"200\",\"state\":\"5\",\"data\":[{\"time\":\"2026-09-16 09:30:00\","
                + "\"context\":\"派件中 快递员 张三 电话 +86 138 & 请保持电话畅通\"}]}}";

        HttpResponse<String> bad = post(sid, param, md5(param + "not-the-salt"));
        assertNotEquals(401, bad.statusCode(), "回调被鉴权挡住了：" + bad.body());
        assertNotEquals(403, bad.statusCode(), bad.body());
        assertTrue(bad.body().startsWith("{\"result\":false"),
                "验签失败必须回 result=false，且报文是 JSON 对象而不是被再序列化一遍的 JSON 字符串：" + bad.body());
        assertEquals(null, lastContext(sid), "验签失败的推送不该写进任何东西");

        HttpResponse<String> ok = post(sid, param, md5(param + salt));
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().startsWith("{\"result\":true"),
                "表单解码后的 param 必须与签名那一串逐字相同，否则永远验不过：" + ok.body());
        assertEquals("派件中 快递员 张三 电话 +86 138 & 请保持电话畅通", lastContext(sid));
        assertEquals(5, jdbc.queryForObject("SELECT track_state FROM donate_shipment WHERE id = ?", Integer.class, sid));
    }

    private long insertSubscribedShipment(String no, String salt) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO donate_shipment (biz_type, biz_id, "
                    + "donor_volunteer_id, donor_name, express_code, express_company, express_no, status, ship_time, "
                    + "subscribe_status, subscribe_salt, subscribe_attempts, subscribe_time, create_time) "
                    + "VALUES (1, 1, 1, '接口用例', 'shunfeng', '顺丰速运', ?, 1, ?, 1, ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            LocalDateTime now = LocalDateTime.now().withNano(0);
            ps.setString(1, no);
            ps.setObject(2, now);
            ps.setString(3, salt);
            ps.setObject(4, now);
            ps.setObject(5, now);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private String lastContext(long sid) {
        return jdbc.queryForObject("SELECT track_last_context FROM donate_shipment WHERE id = ?", String.class, sid);
    }

    private HttpResponse<String> post(long sid, String param, String sign) throws Exception {
        String form = "param=" + URLEncoder.encode(param, StandardCharsets.UTF_8)
                + "&sign=" + URLEncoder.encode(sign, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/callback/logistics/kuaidi100?sid=" + sid))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String md5(String s) throws Exception {
        return HexFormat.of().withUpperCase()
                .formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
