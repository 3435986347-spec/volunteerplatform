package com.hengde.donate.logistics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hengde.donate.config.DonateLogisticsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Set;

/**
 * 快递100 订阅推送（{@code poll}）——V3 物流推送批。
 *
 * <p>订阅协议：表单 POST {@code schema=json&param=...}，param 里是 {@code company / number / key}
 * 与 {@code parameters.callbackurl / salt / resultv2 / phone}。推送回调：表单 POST {@code param / sign}，
 * {@code sign = MD5(param + salt)} 大写——验签在 donate 的服务里做（{@link #callbackSign} 只算期望值）。</p>
 *
 * <p><b>返回码的分流是关键设计</b>（同实名核验那一条）：</p>
 * <ul>
 *   <li>{@code 200} 受理；{@code 501}「重复订阅」<b>也算受理</b>——上一次其实已经订上、只是应答没收到，
 *       当成失败会让它无限重试，每次都再付一遍钱（如果重复订阅计费的话）；</li>
 *   <li>{@code 700 / 701 / 702}（快递公司不支持 / 单号被拒 / 识别不了）<b>再试也没用</b>，直接放弃、交还轮询；</li>
 *   <li>其余（含 {@code 600 / 601} 授权 key 错误或过期、{@code 500} 服务繁忙）按可重试处理——
 *       key 配错是整批的问题，改好配置之后这些运单还应该能订上，不该每一单都被判死。</li>
 * </ul>
 *
 * @author hengde
 */
@Slf4j
@Component
public class Kuaidi100PushClient implements LogisticsPushClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PERMANENT = Set.of("700", "701", "702");

    private DonateLogisticsProperties properties;
    private volatile HttpClient http;

    @Autowired
    public void setProperties(DonateLogisticsProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean enabled() {
        DonateLogisticsProperties.Push p = properties.getPush();
        return p.isEnabled() && StringUtils.hasText(properties.getKuaidi100().getKey())
                && StringUtils.hasText(p.getCallbackUrl());
    }

    @Override
    public SubscribeResult subscribe(String companyCode, String expressNo, String phone, String callbackUrl,
                                     String salt) {
        if (!enabled()) {
            throw new LogisticsClient.LogisticsException("物流订阅推送未开通");
        }
        DonateLogisticsProperties.Kuaidi100 k = properties.getKuaidi100();
        String param = buildSubscribeParam(companyCode, expressNo, phone, k.getKey(), callbackUrl, salt);
        String form = "schema=json&param=" + URLEncoder.encode(param, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(properties.getPush().getEndpoint()))
                .timeout(Duration.ofMillis(k.getReadTimeoutMs()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> resp = client(k).send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new LogisticsClient.LogisticsException("快递100 订阅 HTTP " + resp.statusCode());
            }
            return parseSubscribe(resp.body());
        } catch (IOException e) {
            throw new LogisticsClient.LogisticsException("快递100 订阅网络失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LogisticsClient.LogisticsException("快递100 订阅被中断", e);
        }
    }

    static String buildSubscribeParam(String companyCode, String expressNo, String phone, String key,
                                      String callbackUrl, String salt) {
        ObjectNode p = JSON.createObjectNode();
        p.put("company", companyCode);
        p.put("number", expressNo);
        p.put("key", key);
        ObjectNode params = p.putObject("parameters");
        params.put("callbackurl", callbackUrl);
        params.put("salt", salt);
        params.put("resultv2", "1");
        if (StringUtils.hasText(phone)) {
            params.put("phone", phone);
        }
        return p.toString();
    }

    /** 解析订阅应答：{@code {"result":true,"returnCode":"200","message":"提交成功"}}。 */
    static SubscribeResult parseSubscribe(String body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw new LogisticsClient.LogisticsException("快递100 订阅应答不是 JSON");
        }
        String code = root.path("returnCode").asText("");
        String message = root.path("message").asText("");
        if ("200".equals(code) || "501".equals(code)) {
            return new SubscribeResult(true, false, code, message);
        }
        return new SubscribeResult(false, PERMANENT.contains(code), code, message);
    }

    /**
     * 解析推送报文里的 {@code param}：
     * {@code {"status":"polling|shutdown|abort|updateall","message":"...","lastResult":{"nu":"...","com":"...","state":"0","status":"200","data":[...]}}}。
     *
     * <p>{@code lastResult} 与实时查询的应答同一格式，复用查询那一段解析；它缺失或解析不了时 {@code result} 为 null——
     * 例如 {@code abort} 那一类推送往往只有一句原因，<b>状态变化仍要照常处理</b>。</p>
     */
    public static PushedCallback parseCallback(String param) {
        JsonNode root;
        try {
            root = JSON.readTree(param);
        } catch (IOException e) {
            throw new LogisticsClient.LogisticsException("快递100 推送报文不是 JSON");
        }
        if (root == null || !root.isObject()) {
            throw new LogisticsClient.LogisticsException("快递100 推送报文格式不对");
        }
        JsonNode last = root.path("lastResult");
        LogisticsClient.TrackResult result = null;
        if (last.isObject()) {
            try {
                result = Kuaidi100LogisticsClient.parse(last.toString());
            } catch (LogisticsClient.LogisticsException e) {
                result = null;
            }
        }
        return new PushedCallback(root.path("status").asText(""), root.path("message").asText(""),
                last.path("nu").asText(""), last.path("com").asText(""), result);
    }

    /**
     * 一条推送。
     *
     * @param status  polling 监控中 / shutdown 结束 / abort 中止 / updateall 重推
     * @param number  lastResult 里的单号——必须与运单对得上，否则拒收
     * @param result  轨迹；解析不了时为 null
     */
    public record PushedCallback(String status, String message, String number, String company,
                                 LogisticsClient.TrackResult result) {
    }

    /** 推送回调的期望签名：{@code MD5(param + salt)} 大写。 */
    public static String callbackSign(String param, String salt) {
        return Kuaidi100LogisticsClient.md5Upper((param == null ? "" : param) + (salt == null ? "" : salt));
    }

    /** 常量时间比较（签名比对不要用 equals，免得按字节提前返回泄露前缀）。 */
    public static boolean signMatches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.trim().toUpperCase().getBytes(StandardCharsets.UTF_8));
    }

    private HttpClient client(DonateLogisticsProperties.Kuaidi100 k) {
        HttpClient c = http;
        if (c == null) {
            synchronized (this) {
                if (http == null) {
                    http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(k.getConnectTimeoutMs())).build();
                }
                c = http;
            }
        }
        return c;
    }
}
