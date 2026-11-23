package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.service.BookCampaignService;
import com.hengde.donate.service.DonateLogisticsPushService;
import com.hengde.donate.service.DonateShipmentService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hengde.donate.BookDonationTestSupport.expressNo;
import static com.hengde.donate.BookDonationTestSupport.item;
import static com.hengde.donate.BookDonationTestSupport.phone;
import static com.hengde.donate.BookDonationTestSupport.shipment;
import static com.hengde.donate.DonateLogisticsPushTest.md5;
import static com.hengde.donate.DonateLogisticsPushTest.pushParam;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物流推送压测（{@code @Tag("stress")}，默认不跑；{@code -Dtest.excludedGroups=none -Dgroups=stress}）。
 *
 * <p>两段：先让多个订阅任务<b>同时</b>扫同一批在途运单，再把每张运单的一串推送<b>打乱顺序、带重复</b>地并发投进来。
 * 不断言谁赢，只断言不变量：</p>
 * <ol>
 *   <li>每张运单<b>恰好被订阅一次</b>（订阅是付费项）；</li>
 *   <li>每张运单最终的快照是<b>最新的那一条推送</b>——乱序与并发都不能把它盖回旧的；</li>
 *   <li>所有合法推送都回成功（否则快递100 会一直重推），<b>没有任何异常</b>——死锁也算失败。</li>
 * </ol>
 *
 * @author hengde
 */
@Tag("stress")
@SpringBootTest(properties = {
        "hengde.donate.logistics.push.enabled=true",
        "hengde.donate.logistics.push.callback-url=https://example.test/api/callback/logistics/kuaidi100",
        "hengde.donate.logistics.push.subscribe-batch=5000",
        "hengde.donate.logistics.poll-batch=5000"})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, DonateLogisticsPushTest.Fakes.class})
class LogisticsPushStressTest {

    private static final int SHIPMENTS = 40;
    private static final int PUSHES_PER_SHIPMENT = 12;
    private static final int THREADS = 16;

    @Autowired
    private DonateLogisticsPushService pushService;
    @Autowired
    private DonateShipmentService shipmentService;
    @Autowired
    private BookCampaignService campaignService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DonateLogisticsPushTest.FakePush push;

    @Test
    void concurrentSubscribeAndShuffledPushes_keepEveryInvariant() throws Exception {
        push.reset();
        push.delayMs = 5;
        Long campaign = BookDonationTestSupport.openCampaign(campaignService);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < SHIPMENTS; i++) {
            Long donor = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "压测捐赠人" + i, phone(), true);
            ids.add(shipmentService.register(donor, campaign,
                    shipment(expressNo(), item("书", DonateFlow.TYPE_BOOK, 1, null))).getId());
        }
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();

        // ---- 第一段：6 个订阅任务同时扫 ----
        long t0 = System.currentTimeMillis();
        runAll(6, () -> {
            pushService.subscribeDue();
            return null;
        }, failures);
        long subscribeWall = System.currentTimeMillis() - t0;
        for (Long id : ids) {
            assertEquals(1, push.countFor(no(id)), "同时扫的订阅任务也只能订一次：" + id);
        }

        // ---- 第二段：每单 12 条推送（节点时间递增）+ 每条重复一次，全部打乱并发投递 ----
        Map<Long, LocalDateTime> newest = new TreeMap<>();
        List<Runnable> deliveries = new ArrayList<>();
        Random rnd = new Random(20260917L);
        LocalDateTime base = LocalDateTime.of(2026, 9, 10, 8, 0);
        for (Long id : ids) {
            String no = no(id);
            String salt = jdbc.queryForObject("SELECT subscribe_salt FROM donate_shipment WHERE id = ?", String.class, id);
            for (int k = 0; k < PUSHES_PER_SHIPMENT; k++) {
                LocalDateTime at = base.plusHours(k * 3L + rnd.nextInt(2));
                newest.merge(id, at, (a, b) -> a.isAfter(b) ? a : b);
                String param = pushParam("polling", no, k == PUSHES_PER_SHIPMENT - 1 ? 5 : 0, at, "节点" + k,
                        base, "已揽收");
                String sign = md5(param + salt);
                Runnable r = () -> {
                    DonateLogisticsPushService.Ack ack = pushService.handleCallback(id, param, sign);
                    outcomes.computeIfAbsent(ack.success() ? "成功" : "失败·" + ack.message(),
                            x -> new AtomicInteger()).incrementAndGet();
                };
                deliveries.add(r);
                deliveries.add(r);   // 快递100 会重推
            }
        }
        Collections.shuffle(deliveries, rnd);
        t0 = System.currentTimeMillis();
        runEach(deliveries, failures);
        long pushWall = System.currentTimeMillis() - t0;

        System.out.println("\n==== 物流推送压测：订阅 " + SHIPMENTS + " 单 / 6 个任务并发 " + subscribeWall + " ms；推送 "
                + deliveries.size() + " 条 / " + THREADS + " 线程 / " + pushWall + " ms（"
                + String.format("%.1f", deliveries.size() * 1000.0 / Math.max(1, pushWall)) + " 条/s）====\n  "
                + new TreeMap<>(outcomes));

        assertTrue(failures.isEmpty(), "不应有任何异常（死锁也算）：" + failures);
        assertEquals(deliveries.size(), outcomes.getOrDefault("成功", new AtomicInteger()).get(),
                "合法推送（含乱序与重复）都该回成功：" + outcomes);
        for (Long id : ids) {
            LocalDateTime last = jdbc.queryForObject("SELECT track_last_time FROM donate_shipment WHERE id = ?",
                    LocalDateTime.class, id);
            assertEquals(newest.get(id), last, "最终快照必须是最新那条推送，乱序与并发不能把它盖回旧的：" + id);
            assertEquals(DonateFlow.SUBSCRIBE_ACTIVE,
                    jdbc.queryForObject("SELECT subscribe_status FROM donate_shipment WHERE id = ?", Integer.class, id));
        }
    }

    private String no(Long id) {
        return jdbc.queryForObject("SELECT express_no FROM donate_shipment WHERE id = ?", String.class, id);
    }

    private void runAll(int n, java.util.concurrent.Callable<Void> task, List<String> failures) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                try {
                    start.await();
                    task.call();
                } catch (Exception e) {
                    failures.add(e.toString());
                }
            }));
        }
        start.countDown();
        for (Future<?> f : fs) {
            f.get(5, TimeUnit.MINUTES);
        }
        pool.shutdownNow();
    }

    private void runEach(List<Runnable> tasks, List<String> failures) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> fs = new ArrayList<>();
        for (Runnable r : tasks) {
            fs.add(pool.submit(() -> {
                try {
                    start.await();
                    r.run();
                } catch (Exception e) {
                    failures.add(e.toString());
                }
            }));
        }
        start.countDown();
        for (Future<?> f : fs) {
            f.get(5, TimeUnit.MINUTES);
        }
        pool.shutdownNow();
    }
}
