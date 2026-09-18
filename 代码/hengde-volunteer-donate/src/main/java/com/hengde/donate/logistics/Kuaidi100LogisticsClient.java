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
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 快递100 实时查询（{@code poll/query.do}）。
 *
 * <p>协议：表单 POST {@code customer / sign / param}，其中 {@code param} 是 JSON 串，
 * {@code sign = MD5(param + key + customer)} 转大写。<b>签名算的是发出去的那一串 param</b>——
 * 所以 param 只序列化一次、签名与请求体共用同一个字符串，不能各自序列化（字段顺序一变签名就对不上）。</p>
 *
 * <p><b>「查无结果」不是错误</b>：刚寄出还没揽收时快递100 返回 {@code returnCode=500}「查询无结果」，
 * 这里把它当成空轨迹返回；签名错、服务繁忙、网络失败才抛 {@link LogisticsException}，
 * 由调用方记下查询时间、保留旧快照——外部不可用时页面仍要能看（V3规划·捐书批）。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class Kuaidi100LogisticsClient implements LogisticsClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DonateLogisticsProperties properties;
    private volatile HttpClient http;

    @Autowired
    public void setProperties(DonateLogisticsProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean enabled() {
        DonateLogisticsProperties.Kuaidi100 k = properties.getKuaidi100();
        return k.isEnabled() && StringUtils.hasText(k.getCustomer()) && StringUtils.hasText(k.getKey());
    }

    @Override
    public TrackResult query(String companyCode, String expressNo, String phone) {
        if (!enabled()) {
            throw new LogisticsException("物流查询未开通");
        }
        DonateLogisticsProperties.Kuaidi100 k = properties.getKuaidi100();
        String param = buildParam(companyCode, expressNo, phone);
        String form = "customer=" + enc(k.getCustomer()) + "&sign=" + enc(sign(param, k.getKey(), k.getCustomer()))
                + "&param=" + enc(param);
        HttpRequest req = HttpRequest.newBuilder(URI.create(k.getEndpoint()))
                .timeout(Duration.ofMillis(k.getReadTimeoutMs()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> resp = client(k).send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new LogisticsException("快递100 HTTP " + resp.statusCode());
            }
            return parse(resp.body());
        } catch (IOException e) {
            throw new LogisticsException("快递100 网络失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LogisticsException("快递100 查询被中断", e);
        }
    }

    /** param 只序列化一次：签名与请求体必须是同一串。 */
    static String buildParam(String companyCode, String expressNo, String phone) {
        ObjectNode p = JSON.createObjectNode();
        p.put("com", companyCode);
        p.put("num", expressNo);
        if (StringUtils.hasText(phone)) {
            p.put("phone", phone);
        }
        p.put("resultv2", "1");
        return p.toString();
    }

    /** {@code MD5(param + key + customer)}，大写十六进制。 */
    static String sign(String param, String key, String customer) {
        return md5Upper(param + key + customer);
    }

    /** MD5，大写十六进制（快递100 的查询签名与推送回调签名都用它）。 */
    static String md5Upper(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().withUpperCase().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 解析快递100 的响应。成功：{@code status=200}、{@code state}、{@code data[]}（新的在前）；
     * 「查无结果」：{@code result=false, returnCode=500} → 空轨迹；其余 {@code result=false} → 异常。
     */
    static TrackResult parse(String body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw new LogisticsException("快递100 响应不是 JSON");
        }
        if (root.has("result") && !root.get("result").asBoolean(true)) {
            String code = root.path("returnCode").asText("");
            if ("500".equals(code)) {
                return new TrackResult(null, List.of(), body);
            }
            throw new LogisticsException("快递100 拒绝：" + code + " " + root.path("message").asText(""));
        }
        if (!"200".equals(root.path("status").asText(""))) {
            throw new LogisticsException("快递100 状态异常：" + root.path("status").asText("") + " "
                    + root.path("message").asText(""));
        }
        Integer state = root.hasNonNull("state") && StringUtils.hasText(root.get("state").asText())
                ? Integer.valueOf(root.get("state").asText()) : null;
        List<TrackNode> nodes = new ArrayList<>();
        for (JsonNode n : root.path("data")) {
            nodes.add(new TrackNode(parseTime(n.path("time").asText(n.path("ftime").asText(""))),
                    n.path("context").asText("")));
        }
        return new TrackResult(state, nodes, body);
    }

    private static LocalDateTime parseTime(String s) {
        try {
            return StringUtils.hasText(s) ? LocalDateTime.parse(s, TIME) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
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

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
