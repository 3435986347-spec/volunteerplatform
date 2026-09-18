package com.hengde.api.stress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP 全链路压测的小工具：真实走 Tomcat → Sa-Token 路由鉴权 → Jackson → 控制器 → 服务 → MySQL/Redis。
 *
 * <p><b>为什么不用 MockMvc</b>：MockMvc 不经过真实的连接池与 Tomcat 线程池，而压测要看的恰恰是
 * 「Hikari 只有 10 条连接、Tomcat 有 200 个线程」时请求在哪里排队。</p>
 *
 * <p>每个请求的耗时按「标签」（如 {@code POST /v/donate/orders}）归档，结束时打印 p50/p95/p99/max，
 * 并把三类结果分开计数——它们的含义完全不同，混在一起的「错误率」没有意义：</p>
 * <ul>
 *   <li><b>ok</b>：HTTP 200 且 {@code code=200}；</li>
 *   <li><b>rejected</b>：HTTP 400 且 {@code code=400}——业务拒绝（库存不足、状态不对），压测里是正常现象；
 *       但<b>按文案分类打印</b>——「拒绝」合法与否要看是哪一种；</li>
 *   <li><b>failed</b>：其余一切（5xx、401/403、超时、连接失败、响应不是 JSON）——<b>任何一个都应让用例变红</b>。</li>
 * </ul>
 *
 * @author hengde
 */
public final class ApiStressClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String base;
    private final Map<String, ConcurrentLinkedQueue<Long>> latencies = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> ok = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> rejected = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failed = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> rejectReasons = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> failureSamples = new ConcurrentLinkedQueue<>();

    public ApiStressClient(int port) {
        this.base = "http://localhost:" + port + "/api";
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** 一次调用的结果：HTTP 状态、业务码、data 节点。 */
    public record Resp(int http, int code, JsonNode data, String message, long millis) {

        public boolean ok() {
            return http == 200 && code == 200;
        }

        /** data 里的 id（本项目 Long 一律序列化成字符串，见 JacksonConfig）。 */
        public long dataAsLong() {
            return Long.parseLong(data.asText());
        }
    }

    // ---------------- 登录 ----------------

    /** 管理端登录，返回 token（{@code Result<String>}）。 */
    public String adminLogin(String username, String password) {
        Resp r = call("POST", "/a/auth/login", null,
                Map.of("username", username, "password", password), "POST /a/auth/login");
        requireOk(r, "管理员登录");
        return r.data().asText();
    }

    /** 志愿者开发登录（需 {@code hengde.auth.dev-login-enabled=true}），直接造已实名身份。 */
    public String devLogin(String key) {
        Resp r = call("POST", "/v/auth/login/dev", null,
                Map.of("key", key, "registered", true), "POST /v/auth/login/dev");
        requireOk(r, "开发登录 " + key);
        return r.data().get("token").asText();
    }

    // ---------------- 调用 ----------------

    public Resp get(String path, String token, String label) {
        return call("GET", path, token, null, label);
    }

    public Resp post(String path, String token, Object body, String label) {
        return call("POST", path, token, body, label);
    }

    public Resp put(String path, String token, Object body, String label) {
        return call("PUT", path, token, body, label);
    }

    public Resp delete(String path, String token, String label) {
        return call("DELETE", path, token, null, label);
    }

    /**
     * 发一个请求并归档耗时与结果类别。<b>不抛异常</b>——压测线程里抛出去只会让统计漏掉那一次，
     * 所有异常都记成 failed 并留样本。
     */
    public Resp call(String method, String path, String token, Object body, String label) {
        long t0 = System.nanoTime();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json");
            if (token != null) {
                b.header("Authorization", token);
            }
            HttpRequest.BodyPublisher pub = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
            b.method(method, pub);
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            latencies.computeIfAbsent(label, k -> new ConcurrentLinkedQueue<>()).add(ms);
            JsonNode root = parse(resp.body());
            int code = root == null || !root.has("code") ? -1 : root.get("code").asInt();
            String msg = root == null || !root.has("message") ? resp.body() : root.get("message").asText();
            JsonNode data = root == null ? null : root.get("data");
            Resp r = new Resp(resp.statusCode(), code, data, msg, ms);
            classify(label, r, method + " " + path);
            return r;
        } catch (IOException | InterruptedException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            latencies.computeIfAbsent(label, k -> new ConcurrentLinkedQueue<>()).add(ms);
            counter(failed, label).incrementAndGet();
            failureSamples.add(method + " " + path + " → " + e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Resp(-1, -1, null, e.toString(), ms);
        }
    }

    private void classify(String label, Resp r, String what) {
        if (r.ok()) {
            counter(ok, label).incrementAndGet();
        } else if (r.http() == 400 && r.code() == 400) {
            counter(rejected, label).incrementAndGet();
            // 拒绝原因按文案归档：「拒绝」合法与否要看是哪一种——库存不足是正常的，
            // 「这张卷刚刚已不可用」大量出现就说明预检与 CAS 的时间源或条件对不上
            counter(rejectReasons, label + " ⟶ " + r.message()).incrementAndGet();
        } else {
            counter(failed, label).incrementAndGet();
            if (failureSamples.size() < 50) {
                failureSamples.add(what + " → HTTP " + r.http() + " code=" + r.code() + " msg=" + r.message());
            }
        }
    }

    private static AtomicInteger counter(Map<String, AtomicInteger> m, String label) {
        return m.computeIfAbsent(label, k -> new AtomicInteger());
    }

    private static JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? null : JSON.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    public static void requireOk(Resp r, String what) {
        if (!r.ok()) {
            throw new IllegalStateException(what + " 失败：HTTP " + r.http() + " code=" + r.code() + " " + r.message());
        }
    }

    // ---------------- 报表 ----------------

    public int totalFailed() {
        return failed.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    public List<String> failureSamples() {
        return new ArrayList<>(failureSamples);
    }

    /** 业务拒绝按「标签 ⟶ 文案」计数，供用例断言「只出现了预期的那几种拒绝」。 */
    public Map<String, Integer> rejectReasons() {
        Map<String, Integer> m = new TreeMap<>();
        rejectReasons.forEach((k, v) -> m.put(k, v.get()));
        return m;
    }

    public long maxLatencyMillis() {
        return latencies.values().stream().flatMap(ConcurrentLinkedQueue::stream)
                .mapToLong(Long::longValue).max().orElse(0);
    }

    /** 打印按标签的延迟分位、结果计数与拒绝原因分布，返回文本以便用例写进断言信息。 */
    public String report(String title, long wallMillis) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%n[API-STRESS] %s（墙钟 %d ms）%n", title, wallMillis));
        sb.append(String.format("  %-44s %7s %6s %6s %6s %7s %7s %7s %7s%n",
                "接口", "请求数", "ok", "拒绝", "失败", "p50", "p95", "p99", "max"));
        int total = 0;
        for (Map.Entry<String, ConcurrentLinkedQueue<Long>> e : new TreeMap<>(latencies).entrySet()) {
            List<Long> xs = new ArrayList<>(e.getValue());
            Collections.sort(xs);
            total += xs.size();
            sb.append(String.format("  %-44s %7d %6d %6d %6d %6dms %6dms %6dms %6dms%n",
                    e.getKey(), xs.size(),
                    counter(ok, e.getKey()).get(), counter(rejected, e.getKey()).get(),
                    counter(failed, e.getKey()).get(),
                    pct(xs, 50), pct(xs, 95), pct(xs, 99), xs.isEmpty() ? 0 : xs.get(xs.size() - 1)));
        }
        sb.append(String.format("  合计 %d 个请求，吞吐约 %.1f req/s%n", total,
                wallMillis == 0 ? 0.0 : total * 1000.0 / wallMillis));
        if (!rejectReasons.isEmpty()) {
            sb.append("  业务拒绝分布：\n");
            rejectReasons().forEach((k, v) -> sb.append(String.format("    %5d × %s%n", v, k)));
        }
        String text = sb.toString();
        System.out.print(text);
        return text;
    }

    private static long pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
