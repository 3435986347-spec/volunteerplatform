package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.constant.WishFlow;
import com.hengde.donate.dao.DonateItemMapper;
import com.hengde.donate.dao.DonateShipmentMapper;
import com.hengde.donate.service.BookCampaignService;
import com.hengde.donate.service.DonateItemService;
import com.hengde.donate.service.DonateShipmentService;
import com.hengde.donate.service.WishService;
import com.hengde.donate.vo.DonateFlowVOs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.hengde.donate.BookDonationTestSupport.ADMIN;
import static com.hengde.donate.BookDonationTestSupport.check;
import static com.hengde.donate.BookDonationTestSupport.expressNo;
import static com.hengde.donate.BookDonationTestSupport.item;
import static com.hengde.donate.BookDonationTestSupport.next;
import static com.hengde.donate.BookDonationTestSupport.phone;
import static com.hengde.donate.BookDonationTestSupport.result;
import static com.hengde.donate.BookDonationTestSupport.shipment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微心愿的并发与隔离（V3 微心愿批）。
 *
 * <p>并发用例一律用 {@code Future.get()} 收每一个结果，并在每一轮断言<b>不变量</b>——
 * 只看最终状态的用例，在「8 个人里 3 个都认领成功了、最后又被覆盖回 1 个」这种缺陷上照样是绿的。
 * 最后一条是<b>确定性</b>的隔离用例：显式 RR 事务里先读建快照、另一连接改完提交、自检快照仍是旧值，
 * 再调被测方法——不靠调度撞运气。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = {WishTestSupport.RECV_NAME, WishTestSupport.RECV_PHONE, WishTestSupport.RECV_ADDRESS})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class WishConcurrencyTest {

    @Autowired
    private WishService wishService;
    @Autowired
    private DonateShipmentService shipmentService;
    @Autowired
    private DonateItemService itemService;
    @Autowired
    private BookCampaignService campaignService;
    @Autowired
    private DonateShipmentMapper shipmentMapper;
    @Autowired
    private DonateItemMapper itemMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    void concurrentClaims_exactlyOneWins() throws Exception {
        Long wishId = wishService.create(WishTestSupport.wish("抢认领-" + next(), "杨小乐", null), ADMIN);
        int n = 8;
        List<Long> vols = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            vols.add(volunteer("抢认领" + i));
        }
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<Callable<String>> tasks = new ArrayList<>();
        for (Long v : vols) {
            tasks.add(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    wishService.claim(wishId, v);
                    return "OK";
                } catch (BusinessException e) {
                    return e.getMessage();
                }
            });
        }
        List<String> results = runAll(tasks);
        assertEquals(1, results.stream().filter("OK"::equals).count(), results.toString());
        assertTrue(results.stream().filter(r -> !"OK".equals(r)).allMatch(r -> r.contains("别人认领")), results.toString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM donate_wish_claim WHERE wish_id = ? AND status = ?",
                Integer.class, wishId, WishFlow.CLAIM_ACTIVE));
        assertEquals(WishFlow.WISH_CLAIMED, wishStatus(wishId));
    }

    /**
     * 同一个人一边取消认领、一边登记寄出：两者都先锁住那条认领，<b>必然恰好一个成功</b>——
     * 若都成功，就有一包东西挂在一条已取消的认领下，送不出去也没人管。
     */
    @Test
    void cancelRacingRegister_exactlyOneWins_andNoGoodsOnAnEndedClaim() throws Exception {
        for (int round = 0; round < 15; round++) {
            Long wishId = wishService.create(WishTestSupport.wish("取消对寄出-" + next(), "朱小天", null), ADMIN);
            Long donor = volunteer("边取消边寄" + round);
            wishService.claim(wishId, donor);
            Long claimId = liveClaimId(wishId);
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Callable<String>> tasks = List.of(
                    () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        try {
                            wishService.cancelClaim(wishId, donor);
                            return "CANCEL_OK";
                        } catch (BusinessException e) {
                            return "CANCEL_REJECTED:" + e.getMessage();
                        }
                    },
                    () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        try {
                            wishService.registerShipment(wishId, donor, shipment(expressNo(),
                                    item("书包", DonateFlow.TYPE_STATIONERY, 1, null)));
                            return "SHIP_OK";
                        } catch (BusinessException e) {
                            return "SHIP_REJECTED:" + e.getMessage();
                        }
                    });
            List<String> r = runAll(tasks);
            long oks = r.stream().filter(s -> s.endsWith("_OK")).count();
            assertEquals(1, oks, "第 " + round + " 轮：取消与寄出必然恰好一个成功：" + r);
            int claimStatus = jdbc.queryForObject("SELECT status FROM donate_wish_claim WHERE id = ?", Integer.class, claimId);
            long liveGoods = jdbc.queryForObject("SELECT COUNT(*) FROM donate_item WHERE biz_type = ? AND biz_id = ? "
                    + "AND status NOT IN (?, ?, ?)", Long.class, DonateFlow.BIZ_WISH, claimId,
                    DonateFlow.ITEM_REJECTED, DonateFlow.ITEM_RETURNED, DonateFlow.ITEM_CANCELLED);
            if (claimStatus == WishFlow.CLAIM_CANCELLED) {
                assertEquals(0, liveGoods, "已取消的认领下不能有活着的物资：" + r);
                assertEquals(WishFlow.WISH_OPEN, wishStatus(wishId));
            } else {
                assertEquals(WishFlow.CLAIM_ACTIVE, claimStatus);
                assertEquals(1, liveGoods);
                assertEquals(WishFlow.WISH_CLAIMED, wishStatus(wishId));
            }
        }
    }

    /**
     * 实现与「认领人取消在途包裹」赛跑：实现只在在途包裹已取消之后才可能成功；
     * 无论谁先，都不能出现「心愿已实现、却还有物资在途 / 待核对 / 合格未发」。
     */
    @Test
    void realizeRacingParcelCancel_neverRealizesOverUnsettledGoods() throws Exception {
        for (int round = 0; round < 10; round++) {
            Long wishId = wishService.create(WishTestSupport.wish("实现对取消-" + next(), "秦小雨", null), ADMIN);
            Long donor = volunteer("实现赛跑" + round);
            wishService.claim(wishId, donor);
            DonateFlowVOs.Shipment settled = wishService.registerShipment(wishId, donor, shipment(expressNo(),
                    item("书包", DonateFlow.TYPE_STATIONERY, 1, null)));
            DonateFlowVOs.Shipment inTransit = wishService.registerShipment(wishId, donor, shipment(expressNo(),
                    item("文具", DonateFlow.TYPE_STATIONERY, 1, null)));
            shipmentService.arrive(settled.getId(), ADMIN);
            Long bag = settled.getItems().get(0).getId();
            shipmentService.check(settled.getId(), check(result(bag, true, null)), ADMIN);
            itemService.generateCode(bag, ADMIN);
            Long claimId = liveClaimId(wishId);

            CyclicBarrier barrier = new CyclicBarrier(2);
            List<String> r = runAll(List.of(
                    () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        try {
                            wishService.realize(wishId, List.of("https://cdn.example.com/r.jpg"), ADMIN);
                            return "REALIZE_OK";
                        } catch (BusinessException e) {
                            return "REALIZE_REJECTED:" + e.getMessage();
                        }
                    },
                    () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        shipmentService.cancel(inTransit.getId(), donor);
                        return "CANCEL_OK";
                    }));
            assertTrue(r.contains("CANCEL_OK"), r.toString());
            if (wishStatus(wishId) == WishFlow.WISH_REALIZED) {
                long unsettled = jdbc.queryForObject("SELECT COUNT(*) FROM donate_item WHERE biz_type = ? AND biz_id = ? "
                        + "AND status IN (?, ?, ?)", Long.class, DonateFlow.BIZ_WISH, claimId,
                        DonateFlow.ITEM_PENDING, DonateFlow.ITEM_ARRIVED, DonateFlow.ITEM_QUALIFIED);
                assertEquals(0, unsettled, "已实现的心愿下不能有在途 / 待核对 / 合格未发的物资：" + r);
            } else {
                assertTrue(r.stream().anyMatch(s -> s.startsWith("REALIZE_REJECTED") && s.contains("未到货或未核对")),
                        "没实现只可能是因为在途包裹还在：" + r);
            }
        }
    }

    /**
     * 后台补录物资读运单头必须是<b>当前读</b>（确定性复现，不靠调度）。
     *
     * <p>RR 事务里先读一次（快照定在「已到货」）→ 另一连接把包裹核对完并提交 → 自检快照仍是「已到货」→
     * 同一事务里补录。新物资必须按「已核对」落成<b>合格</b>；若按快照落成「待核对」，
     * 就进了一个已经核对完的包裹，永远没人再核它。{@code addItem} 前面新加了一次普通读（查是不是微心愿包裹），
     * 正是它让读视图提前定型——改之前的代码第一条语句是当前读的 UPDATE，这个坑不存在。</p>
     */
    @Test
    void addItem_readsShipmentHeadCurrently_evenWhenTheSnapshotIsStale() throws Exception {
        Long campaign = BookDonationTestSupport.openCampaign(campaignService);
        Long donor = volunteer("补录隔离");
        DonateFlowVOs.Shipment s = shipmentService.register(donor, campaign, shipment(expressNo(),
                item("小王子", DonateFlow.TYPE_BOOK, 1, null)));
        shipmentService.arrive(s.getId(), ADMIN);
        Long first = s.getItems().get(0).getId();

        TransactionTemplate rr = new TransactionTemplate(txManager);
        rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            Long added = rr.execute(st -> {
                assertEquals(DonateFlow.SHIPMENT_ARRIVED, shipmentMapper.selectById(s.getId()).getStatus());
                try {
                    other.submit(() -> shipmentService.check(s.getId(), check(result(first, true, null)), ADMIN))
                            .get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                assertEquals(DonateFlow.SHIPMENT_ARRIVED, shipmentMapper.selectById(s.getId()).getStatus(),
                        "用例有效性自检：快照仍读到「已到货」，否则这条用例测不到任何东西");
                return shipmentService.addItem(s.getId(), item("补录的书", DonateFlow.TYPE_BOOK, 1, null), ADMIN);
            });
            assertEquals(DonateFlow.ITEM_QUALIFIED, itemMapper.selectById(added).getStatus(),
                    "包裹已核对：补录的物资应直接算合格，而不是以「待核对」落进一个没人会再核的包裹");
        } finally {
            other.shutdownNow();
        }
    }

    // ---------- helpers ----------

    private Long volunteer(String name) {
        return BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, name, phone(), true);
    }

    private int wishStatus(Long id) {
        return jdbc.queryForObject("SELECT status FROM donate_wish WHERE id = ?", Integer.class, id);
    }

    private Long liveClaimId(Long wishId) {
        return jdbc.queryForObject("SELECT id FROM donate_wish_claim WHERE wish_id = ? AND status IN (?, ?)",
                Long.class, wishId, WishFlow.CLAIM_ACTIVE, WishFlow.CLAIM_REALIZED);
    }

    private static List<String> runAll(List<Callable<String>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<String>> fs = new ArrayList<>();
            for (Callable<String> t : tasks) {
                fs.add(pool.submit(t));
            }
            List<String> out = new ArrayList<>();
            for (Future<String> f : fs) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
