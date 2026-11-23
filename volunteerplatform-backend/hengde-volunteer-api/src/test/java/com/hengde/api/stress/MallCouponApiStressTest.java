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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 积分商城 + 卷的 <b>HTTP 全链路压测</b>（V3 卷批）。
 *
 * <p>60 个志愿者并发地「浏览 → 查可用卷 → 下单（带卷）→ 看我的兑换 → 偶尔取消」，
 * 同时一名管理员在后台随机通过 / 驳回待审单。结束后用 SQL 核对四本账（订单 / 库存 / 卷 / 积分）。</p>
 *
 * <p><b>默认不跑</b>（{@code @Tag("stress")}）。要跑：</p>
 * <pre>./mvnw test -Dtest.excludedGroups=none -Dgroups=stress -Dtest=MallCouponApiStressTest \
 *     -f ../hengde-volunteer-api/pom.xml</pre>
 *
 * <p>判定标准：<b>失败（5xx / 401 / 403 / 超时）必须为 0</b>，业务拒绝（400）不计入；
 * 任何请求超过 10 秒视为卡死（锁等待或连接池耗尽）；四本账逐条守恒。
 * 延迟分位只打印、不做硬断言——本机与生产机差得太远，硬阈值只会制造误报。</p>
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
class MallCouponApiStressTest {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int VOLUNTEERS = 60;
    private static final int ITERATIONS = 8;
    private static final int STOCK = 120;
    private static final int PRICE = 30;
    private static final int POINTS_EACH = 500;

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sixtyConcurrentShoppersWithCouponsAndAConcurrentReviewer() throws Exception {
        ApiStressClient c = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        // ---------- 准备：全部经 HTTP，顺带把后台接口也跑一遍 ----------
        long goodsId = c.post("/a/donate/goods", admin, Map.of(
                "name", "压测保温杯-" + tag, "sponsorName", "压测赞助方",
                "specs", List.of(Map.of("name", "标准", "points", PRICE, "stock", STOCK))),
                "POST /a/donate/goods").dataAsLong();
        ApiStressClient.requireOk(c.post("/a/donate/goods/" + goodsId + "/submit", admin, null,
                "POST /a/donate/goods/{id}/submit"), "提交审核");
        ApiStressClient.requireOk(c.post("/a/donate/goods/" + goodsId + "/approve", admin, null,
                "POST /a/donate/goods/{id}/approve"), "审核通过");
        long specId = Long.parseLong(c.get("/a/donate/goods/" + goodsId, admin, "GET /a/donate/goods/{id}")
                .data().get("specs").get(0).get("id").asText());

        long couponId = c.post("/a/donate/coupons", admin, Map.of(
                "name", "压测满减卷-" + tag, "type", 2, "thresholdPoints", 20, "discountPoints", 10,
                "validStart", LocalDateTime.now().minusDays(1).format(FMT),
                "validEnd", LocalDateTime.now().plusDays(30).format(FMT)), "POST /a/donate/coupons").dataAsLong();

        List<String> tokens = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < VOLUNTEERS; i++) {
            String key = "stress-" + tag + "-" + i;
            tokens.add(c.devLogin(key));
            Long vid = jdbc.queryForObject("SELECT id FROM volunteer WHERE openid = ?", Long.class, "dev:" + key);
            ids.add(vid);
            ApiStressClient.requireOk(c.post("/a/activity/points/adjust", admin, Map.of(
                    "volunteerId", vid, "changeAmount", POINTS_EACH, "reason", "压测预置",
                    "requestId", "stress-pt-" + tag + "-" + i), "POST /a/activity/points/adjust"), "预置积分");
        }
        JsonNode grant = c.post("/a/donate/coupons/" + couponId + "/grants", admin, Map.of(
                "requestId", "stress-grant-" + tag, "volunteerIds", ids), "POST /a/donate/coupons/{id}/grants").data();
        assertEquals(VOLUNTEERS, grant.get("granted").asInt(), "每人一张卷");

        // ---------- 压测 ----------
        ExecutorService pool = Executors.newFixedThreadPool(VOLUNTEERS + 1);
        CyclicBarrier barrier = new CyclicBarrier(VOLUNTEERS + 1);
        AtomicBoolean shoppersDone = new AtomicBoolean(false);
        List<Future<?>> futures = new ArrayList<>();
        long t0 = System.nanoTime();
        try {
            for (int i = 0; i < VOLUNTEERS; i++) {
                String token = tokens.get(i);
                futures.add(pool.submit(() -> {
                    barrier.await(60, TimeUnit.SECONDS);
                    shop(c, token, goodsId, specId);
                    return null;
                }));
            }
            Future<?> reviewer = pool.submit(() -> {
                barrier.await(60, TimeUnit.SECONDS);
                review(c, admin, shoppersDone);
                return null;
            });
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.MINUTES);
            }
            shoppersDone.set(true);
            reviewer.get(5, TimeUnit.MINUTES);
        } finally {
            pool.shutdownNow();
        }
        long wall = (System.nanoTime() - t0) / 1_000_000;
        String report = c.report("积分商城 + 卷：60 并发志愿者 + 1 审核员", wall);

        // ---------- 判定 ----------
        assertEquals(0, c.totalFailed(), "不允许任何 5xx / 401 / 403 / 超时：" + c.failureSamples() + report);
        assertTrue(c.maxLatencyMillis() < 10_000, "有请求超过 10 秒——锁等待或连接池耗尽" + report);
        assertLedgersBalance(specId, couponId, ids);
    }

    /** 一个志愿者的会话：浏览 → 查可用卷 → 下单 → 看我的兑换 → 偶尔取消。 */
    private static void shop(ApiStressClient c, String token, long goodsId, long specId) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int k = 0; k < ITERATIONS; k++) {
            c.get("/v/donate/goods?page=1&size=10", token, "GET /v/donate/goods");
            c.get("/v/donate/goods/" + goodsId, token, "GET /v/donate/goods/{id}");
            ApiStressClient.Resp usable = c.get("/v/donate/coupons/usable?specId=" + specId, token,
                    "GET /v/donate/coupons/usable");
            Map<String, Object> body = new HashMap<>();
            body.put("specId", specId);
            if (usable.ok() && usable.data().size() > 0 && rnd.nextBoolean()) {
                body.put("couponGrantId", Long.parseLong(usable.data().get(0).get("id").asText()));
            }
            ApiStressClient.Resp placed = c.post("/v/donate/orders", token, body, "POST /v/donate/orders");
            c.get("/v/donate/orders?page=1&size=10", token, "GET /v/donate/orders");
            if (placed.ok() && rnd.nextInt(4) == 0) {
                c.delete("/v/donate/orders/" + placed.data().get("id").asText(), token, "DELETE /v/donate/orders/{id}");
            }
            c.get("/v/donate/coupons/mine?page=1&size=10", token, "GET /v/donate/coupons/mine");
        }
    }

    /** 管理员：一直从待审队列里随机挑单通过或驳回，直到志愿者们都结束、队列也清空。 */
    private static void review(ApiStressClient c, String admin, AtomicBoolean shoppersDone) throws InterruptedException {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        while (true) {
            ApiStressClient.Resp page = c.get("/a/donate/orders?status=0&page=1&size=20", admin, "GET /a/donate/orders");
            JsonNode records = page.ok() ? page.data().get("records") : null;
            if (records == null || records.isEmpty()) {
                if (shoppersDone.get()) {
                    return;
                }
                Thread.sleep(20);
                continue;
            }
            String id = records.get(rnd.nextInt(records.size())).get("id").asText();
            if (rnd.nextBoolean()) {
                c.post("/a/donate/orders/" + id + "/approve", admin, null, "POST /a/donate/orders/{id}/approve");
            } else {
                c.post("/a/donate/orders/" + id + "/reject", admin, Map.of("reason", "压测驳回"),
                        "POST /a/donate/orders/{id}/reject");
            }
        }
    }

    /** 与服务层压测同一组不变式，这次数据是从 HTTP 一路写进来的。 */
    private void assertLedgersBalance(long specId, long couponId, List<Long> volunteers) {
        int active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mall_order WHERE spec_id = ? AND status IN (0, 1, 3)", Integer.class, specId);
        int stock = jdbc.queryForObject("SELECT stock FROM mall_goods_spec WHERE id = ?", Integer.class, specId);
        assertEquals(STOCK, stock + active, "库存守恒：当前库存 + 占着库存的单 = 初始库存");

        int usedGrants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mall_coupon_grant WHERE coupon_id = ? AND status = 1", Integer.class, couponId);
        int activeWithCoupon = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mall_order WHERE spec_id = ? AND coupon_grant_id IS NOT NULL AND status IN (0, 1, 3)",
                Integer.class, specId);
        assertEquals(activeWithCoupon, usedGrants, "被占用的卷 = 用了卷的有效单");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM mall_order o JOIN mall_coupon_grant g ON g.used_order_id = o.id "
                        + "WHERE o.spec_id = ? AND o.status IN (2, 4)", Integer.class, specId),
                "已驳回 / 已取消的单不得还占着卷");

        for (Long v : volunteers) {
            int spent = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(points), 0) FROM mall_order WHERE volunteer_id = ? AND status IN (0, 1, 3)",
                    Integer.class, v);
            int balance = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(change_amount), 0) FROM point_record WHERE volunteer_id = ? AND is_deleted = 0",
                    Integer.class, v);
            assertEquals(POINTS_EACH - spent, balance, "志愿者 " + v + " 账实不符");
        }
    }
}
