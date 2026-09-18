package com.hengde.api.stress;

import com.fasterxml.jackson.databind.JsonNode;
import com.hengde.api.HengdeVolunteerApplication;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公益捐书的 <b>HTTP 全链路压测</b>（V3 捐书批）。
 *
 * <p>50 个捐赠人并发「看活动 → 登记寄出 → 看我的运单 / 捐书记录 / 物流」，同时 4 个仓库操作员并发
 * 「扫快递单号识别 → 到货 → 核对 → 贴码 → 装箱」，最后统一送达。结束后用 SQL 核对：
 * 每件物资都到了终态，统计数字与物资明细对得上。</p>
 *
 * <p><b>默认不跑</b>（{@code @Tag("stress")}）。要跑：</p>
 * <pre>./mvnw test -Dtest.excludedGroups=none -Dgroups=stress -Dtest=BookDonationApiStressTest \
 *     -f ../hengde-volunteer-api/pom.xml</pre>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@Tag("stress")
@SpringBootTest(classes = HengdeVolunteerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "hengde.auth.dev-login-enabled=true")
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class BookDonationApiStressTest {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DONORS = 50;
    private static final int SCANNERS = 4;

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void fiftyDonorsAndFourScannersOverHttp() throws Exception {
        ApiStressClient c = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);
        long org = c.post("/a/donate/recipient-orgs", admin, Map.of("name", "压测小学-" + tag), "POST /a/donate/recipient-orgs")
                .dataAsLong();
        long campaign = c.post("/a/donate/book-campaigns", admin, Map.of(
                "title", "压测捐书-" + tag,
                "startTime", LocalDateTime.now().minusDays(1).format(FMT),
                "endTime", LocalDateTime.now().plusDays(7).format(FMT),
                "recvPhone", "0759-1234567", "recvAddress", "雷州市 转 "), "POST /a/donate/book-campaigns").dataAsLong();
        ApiStressClient.requireOk(c.post("/a/donate/book-campaigns/" + campaign + "/publish", admin, null,
                "POST /a/donate/book-campaigns/{id}/publish"), "发布");
        String boxId = c.post("/a/donate/boxes", admin, Map.of("campaignId", campaign), "POST /a/donate/boxes")
                .data().get("id").asText();

        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < DONORS; i++) {
            tokens.add(c.devLogin("book-stress-" + tag + "-" + i));
        }

        ConcurrentLinkedQueue<String> waybills = new ConcurrentLinkedQueue<>();
        AtomicBoolean donorsDone = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(DONORS + SCANNERS);
        CyclicBarrier barrier = new CyclicBarrier(DONORS + SCANNERS);
        List<Future<?>> donorFutures = new ArrayList<>();
        List<Future<?>> scannerFutures = new ArrayList<>();
        long t0 = System.nanoTime();
        try {
            for (int i = 0; i < DONORS; i++) {
                String token = tokens.get(i);
                int idx = i;
                donorFutures.add(pool.submit(() -> {
                    barrier.await(60, TimeUnit.SECONDS);
                    c.get("/v/donate/book-campaigns?page=1&size=10", token, "GET /v/donate/book-campaigns");
                    c.get("/v/donate/book-campaigns/" + campaign, token, "GET /v/donate/book-campaigns/{id}");
                    String no = "YT" + tag.toUpperCase() + idx;
                    ApiStressClient.Resp r = c.post("/v/donate/book-campaigns/" + campaign + "/shipments", token, Map.of(
                            "expressCode", "yuantong", "expressNo", no,
                            "items", List.of(Map.of("name", "书" + idx, "itemType", 1, "quantity", 2),
                                    Map.of("name", "文具" + idx, "itemType", 2, "quantity", 1))),
                            "POST /v/donate/book-campaigns/{id}/shipments");
                    if (r.ok()) {
                        waybills.add(no);
                    }
                    c.get("/v/donate/shipments/mine?page=1&size=10", token, "GET /v/donate/shipments/mine");
                    c.get("/v/donate/items/mine?page=1&size=10", token, "GET /v/donate/items/mine");
                    if (r.ok()) {
                        c.get("/v/donate/shipments/" + r.data().get("id").asText() + "/track", token,
                                "GET /v/donate/shipments/{id}/track");
                    }
                    return null;
                }));
            }
            for (int s = 0; s < SCANNERS; s++) {
                scannerFutures.add(pool.submit(() -> {
                    barrier.await(60, TimeUnit.SECONDS);
                    while (true) {
                        String no = waybills.poll();
                        if (no == null) {
                            if (donorsDone.get() && waybills.isEmpty()) {
                                return null;
                            }
                            Thread.sleep(10);
                            continue;
                        }
                        JsonNode scan = c.get("/a/donate/scan?code=" + no, admin, "GET /a/donate/scan").data();
                        String shipmentId = scan.get("id").asText();
                        c.post("/a/donate/shipments/" + shipmentId + "/arrive", admin, null, "POST /a/donate/shipments/{id}/arrive");
                        JsonNode items = scan.get("shipments").get(0).get("items");
                        List<Map<String, Object>> results = new ArrayList<>();
                        items.forEach(it -> results.add(Map.of("itemId", it.get("id").asText(), "qualified", true)));
                        c.post("/a/donate/shipments/" + shipmentId + "/check", admin, Map.of("results", results),
                                "POST /a/donate/shipments/{id}/check");
                        for (JsonNode it : items) {
                            String code = c.post("/a/donate/items/" + it.get("id").asText() + "/barcode", admin, null,
                                    "POST /a/donate/items/{id}/barcode").data().get("exclusiveCode").asText();
                            c.post("/a/donate/boxes/" + boxId + "/pack", admin, Map.of("code", code),
                                    "POST /a/donate/boxes/{id}/pack");
                        }
                    }
                }));
            }
            for (Future<?> f : donorFutures) {
                f.get(10, TimeUnit.MINUTES);
            }
            donorsDone.set(true);
            for (Future<?> f : scannerFutures) {
                f.get(10, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
        ApiStressClient.requireOk(c.post("/a/donate/boxes/" + boxId + "/deliver", admin, Map.of("recipientOrgId", org),
                "POST /a/donate/boxes/{id}/deliver"), "送达");
        long wall = (System.nanoTime() - t0) / 1_000_000;
        String report = c.report("公益捐书：50 捐赠人 + 4 仓库操作员", wall);

        assertEquals(0, c.totalFailed(), "不允许任何 5xx / 401 / 403 / 超时：" + c.failureSamples() + report);
        assertTrue(c.rejectReasons().isEmpty(), "这条流水线里不该有任何业务拒绝：" + c.rejectReasons());
        assertTrue(c.maxLatencyMillis() < 10_000, "有请求超过 10 秒——锁等待或连接池耗尽" + report);

        assertEquals(DONORS * 2, jdbc.queryForObject("SELECT COUNT(*) FROM donate_item WHERE biz_id = ? AND status = 6",
                Integer.class, campaign), "每件物资都送达了");
        assertEquals(DONORS, jdbc.queryForObject("SELECT COUNT(*) FROM donate_shipment WHERE biz_id = ? AND status = 3",
                Integer.class, campaign));
        JsonNode stats = c.get("/a/donate/book-campaigns/" + campaign, admin, "GET /a/donate/book-campaigns/{id}")
                .data().get("stats");
        assertEquals(DONORS, stats.get("participants").asInt());
        assertEquals(DONORS * 2, stats.get("books").asInt(), "每人两本书");
        assertEquals(DONORS, stats.get("stationery").asInt());
    }
}
