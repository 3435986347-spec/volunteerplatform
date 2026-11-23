package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.constant.WishFlow;
import com.hengde.donate.service.DonateShipmentService;
import com.hengde.donate.service.WishService;
import com.hengde.donate.vo.DonateFlowVOs;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hengde.donate.BookDonationTestSupport.ADMIN;
import static com.hengde.donate.BookDonationTestSupport.expressNo;
import static com.hengde.donate.BookDonationTestSupport.item;
import static com.hengde.donate.BookDonationTestSupport.next;
import static com.hengde.donate.BookDonationTestSupport.phone;
import static com.hengde.donate.BookDonationTestSupport.shipment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微心愿压力测试（{@code @Tag("stress")}，默认不跑；{@code -Dtest.excludedGroups=none -Dgroups=stress}）。
 *
 * <p>一群人在一小撮心愿上随机地认领、取消认领、登记寄出、取消包裹。不断言「谁成功」，只断言结束时的<b>不变量</b>：
 * ① 每个心愿至多一条「活」认领；② 心愿状态与认领严格对得上（已认领 ⇔ 恰好一条认领中，待认领 ⇔ 没有活认领）；
 * ③ 已取消的认领下没有活着的物资；④ 除业务拒绝外没有任何异常（死锁、500 都算失败）。</p>
 *
 * @author hengde
 */
@Tag("stress")
@SpringBootTest(properties = {WishTestSupport.RECV_NAME, WishTestSupport.RECV_PHONE, WishTestSupport.RECV_ADDRESS})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class WishStressTest {

    private static final int WISHES = 12;
    private static final int VOLUNTEERS = 32;
    private static final int OPS_PER_VOLUNTEER = 20;
    private static final int THREADS = 16;

    @Autowired
    private WishService wishService;
    @Autowired
    private DonateShipmentService shipmentService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void randomClaimCancelShipTraffic_keepsWishesAndClaimsConsistent() throws Exception {
        String tag = "压测心愿-" + next();
        List<Long> wishes = new ArrayList<>();
        for (int i = 0; i < WISHES; i++) {
            wishes.add(wishService.create(WishTestSupport.wish(tag + "-" + i, "压测孩子" + i, null), ADMIN));
        }
        List<Long> vols = new ArrayList<>();
        for (int i = 0; i < VOLUNTEERS; i++) {
            vols.add(BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "压测志愿者" + i, phone(), true));
        }

        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> fs = new ArrayList<>();
        for (Long v : vols) {
            fs.add(pool.submit(() -> {
                start.await();
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int op = 0; op < OPS_PER_VOLUNTEER; op++) {
                    Long wishId = wishes.get(rnd.nextInt(wishes.size()));
                    int dice = rnd.nextInt(100);
                    String kind = dice < 45 ? "认领" : dice < 70 ? "取消认领" : dice < 90 ? "寄出" : "取消包裹";
                    try {
                        switch (kind) {
                            case "认领" -> wishService.claim(wishId, v);
                            case "取消认领" -> wishService.cancelClaim(wishId, v);
                            case "寄出" -> wishService.registerShipment(wishId, v, shipment(expressNo(),
                                    item("书包", DonateFlow.TYPE_STATIONERY, 1, null)));
                            default -> cancelOneOfMyParcels(v);
                        }
                        outcomes.computeIfAbsent(kind + "·成功", k -> new AtomicInteger()).incrementAndGet();
                    } catch (BusinessException e) {
                        outcomes.computeIfAbsent(kind + "·拒绝·" + e.getMessage(), k -> new AtomicInteger())
                                .incrementAndGet();
                    } catch (Exception e) {
                        failures.add(kind + " → " + e);
                    }
                }
                return null;
            }));
        }
        long t0 = System.currentTimeMillis();
        start.countDown();
        for (Future<?> f : fs) {
            f.get(5, TimeUnit.MINUTES);
        }
        long wall = System.currentTimeMillis() - t0;
        pool.shutdownNow();

        int total = VOLUNTEERS * OPS_PER_VOLUNTEER;
        StringBuilder report = new StringBuilder("\n==== 微心愿压测：").append(total).append(" 次操作 / ")
                .append(THREADS).append(" 线程 / ").append(wall).append(" ms（")
                .append(String.format("%.1f", total * 1000.0 / Math.max(1, wall))).append(" ops/s）====\n");
        new TreeMap<>(outcomes).forEach((k, n) -> report.append("  ").append(k).append(" : ").append(n.get()).append('\n'));
        System.out.println(report);

        assertTrue(failures.isEmpty(), "除业务拒绝外不应有任何异常（死锁 / 500）：" + failures);
        assertTrue(outcomes.keySet().stream().anyMatch(k -> k.startsWith("认领·成功")), "压测至少要真的认领成功过");
        for (Long wishId : wishes) {
            int status = jdbc.queryForObject("SELECT status FROM donate_wish WHERE id = ?", Integer.class, wishId);
            int live = jdbc.queryForObject("SELECT COUNT(*) FROM donate_wish_claim WHERE wish_id = ? AND status IN (?, ?)",
                    Integer.class, wishId, WishFlow.CLAIM_ACTIVE, WishFlow.CLAIM_REALIZED);
            int active = jdbc.queryForObject("SELECT COUNT(*) FROM donate_wish_claim WHERE wish_id = ? AND status = ?",
                    Integer.class, wishId, WishFlow.CLAIM_ACTIVE);
            assertTrue(live <= 1, "心愿 " + wishId + " 同时有 " + live + " 条活认领");
            if (status == WishFlow.WISH_CLAIMED) {
                assertEquals(1, active, "心愿 " + wishId + " 已认领却没有恰好一条认领中");
            } else {
                assertEquals(WishFlow.WISH_OPEN, status);
                assertEquals(0, live, "心愿 " + wishId + " 在心愿池里却挂着活认领");
            }
        }
        long stranded = jdbc.queryForObject("SELECT COUNT(*) FROM donate_item i JOIN donate_wish_claim c "
                        + "ON c.id = i.biz_id AND i.biz_type = ? WHERE c.status IN (?, ?) AND i.is_deleted = 0 "
                        + "AND i.status NOT IN (?, ?, ?)", Long.class, DonateFlow.BIZ_WISH,
                WishFlow.CLAIM_CANCELLED, WishFlow.CLAIM_REVOKED,
                DonateFlow.ITEM_REJECTED, DonateFlow.ITEM_RETURNED, DonateFlow.ITEM_CANCELLED);
        assertEquals(0, stranded, "已结束的认领下不能有活着的物资");
    }

    /** 取消自己一个还没到货的包裹（没有就算了）——给「取消认领」腾出路。 */
    private void cancelOneOfMyParcels(Long volunteerId) {
        List<Long> mine = jdbc.queryForList("SELECT id FROM donate_shipment WHERE donor_volunteer_id = ? AND biz_type = ? "
                + "AND status = ? ORDER BY id LIMIT 1", Long.class, volunteerId, DonateFlow.BIZ_WISH,
                DonateFlow.SHIPMENT_SHIPPED);
        if (!mine.isEmpty()) {
            shipmentService.cancel(mine.get(0), volunteerId);
        }
    }

    @SuppressWarnings("unused")
    private static DonateFlowVOs.Shipment unused() {
        return null;
    }
}
