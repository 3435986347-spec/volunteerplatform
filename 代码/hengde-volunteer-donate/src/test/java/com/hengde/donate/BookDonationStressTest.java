package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.dto.DonateItemInputDTO;
import com.hengde.donate.dto.ShipmentCheckDTO;
import com.hengde.donate.service.BookCampaignService;
import com.hengde.donate.service.DonateBoxService;
import com.hengde.donate.service.DonateItemService;
import com.hengde.donate.service.DonateMasterDataService;
import com.hengde.donate.service.DonateShipmentService;
import com.hengde.donate.vo.DonateFlowVOs;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hengde.donate.BookDonationTestSupport.ADMIN;
import static com.hengde.donate.BookDonationTestSupport.item;
import static com.hengde.donate.BookDonationTestSupport.phone;
import static com.hengde.donate.BookDonationTestSupport.result;
import static com.hengde.donate.BookDonationTestSupport.shipment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 捐书批<b>压力测试</b>：几十个捐赠人同时登记、一个仓库里几个人同时扫码到货 / 核对 / 贴码 / 装箱 / 送达。
 *
 * <p><b>默认不跑</b>（{@code @Tag("stress")}）。要跑：</p>
 * <pre>./mvnw test -Dtest.excludedGroups=none -Dgroups=stress -Dtest=BookDonationStressTest -f ../hengde-volunteer-donate/pom.xml</pre>
 *
 * <p>判定：<b>只有 {@link BusinessException} 是可接受的失败</b>（且只能是列出的那几种）；结束时逐条核对
 * 运单 / 物资 / 箱子 / 轨迹四处的不变式——成功数随调度变，不变式不该变。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@Tag("stress")
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class BookDonationStressTest {

    @Autowired
    private BookCampaignService campaignService;
    @Autowired
    private DonateMasterDataService masterDataService;
    @Autowired
    private DonateShipmentService shipmentService;
    @Autowired
    private DonateItemService itemService;
    @Autowired
    private DonateBoxService boxService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 60 个捐赠人同时登记寄出 + 10 个人同时拿同一个快递单号登记。
     *
     * <p>不变式：各自的单全部成功；同一单号恰好一个成功、其余九个都拿到「已经登记过」——
     * 靠的是生成列唯一键 {@code uk_active_express}，不是先查再插。</p>
     */
    @Test
    void manyDonorsRegisterAtOnceAndASharedWaybillWinsOnce() throws Exception {
        Long campaign = BookDonationTestSupport.openCampaign(campaignService);
        List<Long> donors = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            donors.add(BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "压测捐赠人" + i, phone(), true));
        }
        String shared = "SHARED" + BookDonationTestSupport.next();
        long t0 = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(70);
        CyclicBarrier barrier = new CyclicBarrier(70);
        List<Future<String>> fs = new ArrayList<>();
        try {
            for (int i = 0; i < 70; i++) {
                Long donor = donors.get(i);
                boolean sharesWaybill = i >= 60;
                fs.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    try {
                        shipmentService.register(donor, campaign, shipment(
                                sharesWaybill ? shared : BookDonationTestSupport.expressNo(),
                                item("书A", DonateFlow.TYPE_BOOK, 2, null),
                                item("书B", DonateFlow.TYPE_BOOK, 1, null),
                                item("文具", DonateFlow.TYPE_STATIONERY, 3, null)));
                        return "OK";
                    } catch (BusinessException e) {
                        return e.getMessage();
                    }
                }));
            }
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : fs) {
                outcomes.add(f.get(120, TimeUnit.SECONDS));
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            report("70 人同时登记（其中 10 人抢同一单号）", 70, ms, outcomes.stream().filter("OK"::equals).count());
            for (int i = 0; i < 60; i++) {
                assertEquals("OK", outcomes.get(i), "各自单号的登记必须全部成功，第 " + i + " 个：" + outcomes.get(i));
            }
            List<String> sharedOutcomes = outcomes.subList(60, 70);
            assertEquals(1, sharedOutcomes.stream().filter("OK"::equals).count(), "同一快递单号只能登记一次");
            assertTrue(sharedOutcomes.stream().filter(o -> !"OK".equals(o)).allMatch(o -> o.contains("已经登记过")),
                    "输家必须拿到说得清的原因：" + sharedOutcomes);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(61, jdbc.queryForObject("SELECT COUNT(*) FROM donate_shipment WHERE biz_id = ? AND biz_type = 1",
                Integer.class, campaign));
        assertEquals(61 * 3, jdbc.queryForObject("SELECT COUNT(*) FROM donate_item WHERE biz_id = ? AND biz_type = 1",
                Integer.class, campaign), "输家的物资必须随事务一起回滚，不留孤儿行");
    }

    /**
     * 仓库流水线：40 个包裹、160 件物资；6 个人并发扫码到货 + 核对 + 贴码（另有 2 个人随手重复扫到货），
     * 然后 6 个人并发往 5 只箱子里装、1 个人边装边把前 4 只箱子送走。
     *
     * <p>不变式（结束时逐条核对）：</p>
     * <ul>
     *   <li>每个包裹恰好一条到货轨迹、状态已核对；</li>
     *   <li>合格物资最终全部送达（没有卡在「合格未装箱」或「装在已送达箱子里却没送达」）；</li>
     *   <li>送达物资的单位名与其箱子的单位名一致；</li>
     *   <li>每件合格物资恰好一条生成码轨迹、一条送达轨迹；装箱轨迹 ≥ 1（被送达抢先的会换箱重装）。</li>
     * </ul>
     */
    @Test
    void warehousePipelineKeepsEveryItemAccountedFor() throws Exception {
        Long campaign = BookDonationTestSupport.openCampaign(campaignService);
        Long org = BookDonationTestSupport.org(masterDataService);
        List<DonateFlowVOs.Shipment> shipments = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Long donor = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "流水线捐赠人" + i, phone(), true);
            shipments.add(shipmentService.register(donor, campaign, shipment(BookDonationTestSupport.expressNo(),
                    item("书1", DonateFlow.TYPE_BOOK, 1, null), item("书2", DonateFlow.TYPE_BOOK, 1, null),
                    item("书3", DonateFlow.TYPE_BOOK, 1, null), item("球", DonateFlow.TYPE_SPORTS, 1, null))));
        }
        ConcurrentLinkedQueue<DonateFlowVOs.Shipment> inbox = new ConcurrentLinkedQueue<>(shipments);
        ConcurrentLinkedQueue<String> codes = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        AtomicInteger ops = new AtomicInteger();

        // ---- 第一阶段：到货 + 核对 + 贴码，外加两个随手重复扫到货的人 ----
        long t0 = System.nanoTime();
        runAll(8, worker -> {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            if (worker >= 6) {
                for (int k = 0; k < 30; k++) {
                    DonateFlowVOs.Shipment s = shipments.get(rnd.nextInt(shipments.size()));
                    try {
                        shipmentService.arrive(s.getId(), ADMIN);
                    } catch (BusinessException expected) {
                        // 「已于 X 确认到货」——重复扫码被正确拒绝
                    }
                    ops.incrementAndGet();
                }
                return;
            }
            DonateFlowVOs.Shipment s;
            while ((s = inbox.poll()) != null) {
                try {
                    shipmentService.arrive(s.getId(), ADMIN);
                } catch (BusinessException alreadyArrivedByTheOtherScanner) {
                    // 被随手扫的人抢先了，继续核对
                }
                List<ShipmentCheckDTO.ItemResult> rs = new ArrayList<>();
                int rejectIdx = rnd.nextInt(4);
                for (int i = 0; i < s.getItems().size(); i++) {
                    rs.add(result(s.getItems().get(i).getId(), i != rejectIdx, i == rejectIdx ? "压测随机判不合格" : null));
                }
                shipmentService.check(s.getId(), BookDonationTestSupport.check(rs.toArray(new ShipmentCheckDTO.ItemResult[0])), ADMIN);
                for (int i = 0; i < s.getItems().size(); i++) {
                    if (i != rejectIdx) {
                        codes.add(itemService.generateCode(s.getItems().get(i).getId(), ADMIN).getExclusiveCode());
                    }
                }
                ops.addAndGet(3);
            }
        }, unexpected);
        long ms1 = (System.nanoTime() - t0) / 1_000_000;
        report("到货 + 核对 + 贴码（6 人 + 2 个重复扫码）", 8, ms1, ops.get());
        assertTrue(unexpected.isEmpty(), "第一阶段出现意外异常：" + unexpected);
        assertEquals(120, codes.size(), "40 个包裹 × 3 件合格 = 120 个专属码");

        // ---- 第二阶段：6 个人并发装箱，1 个人边装边送走前 4 只箱子 ----
        List<Long> boxes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            boxes.add(boxService.create(campaign, ADMIN).getId());
        }
        Long lastBox = boxes.get(4);
        AtomicInteger packed = new AtomicInteger();
        ops.set(0);
        long t1 = System.nanoTime();
        runAll(7, worker -> {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            if (worker == 6) {
                for (int i = 0; i < 4; i++) {
                    Thread.sleep(15);
                    try {
                        boxService.deliver(boxes.get(i), org, ADMIN);
                    } catch (BusinessException emptyBox) {
                        // 这只箱子恰好还空着：留到最后统一送
                    }
                    ops.incrementAndGet();
                }
                return;
            }
            String code;
            while ((code = codes.poll()) != null) {
                Long target = boxes.get(rnd.nextInt(5));
                try {
                    boxService.pack(target, code, ADMIN);
                } catch (BusinessException e) {
                    if (!e.getMessage().contains("已送达")) {
                        throw e;
                    }
                    boxService.pack(lastBox, code, ADMIN);   // 被送达抢先：换最后那只一直在装的箱子
                }
                packed.incrementAndGet();
                ops.incrementAndGet();
            }
        }, unexpected);
        // 收尾：没送走的箱子（含最后那只）全部送达
        for (Long b : boxes) {
            Integer st = jdbc.queryForObject("SELECT status FROM donate_box WHERE id = ?", Integer.class, b);
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM donate_item WHERE box_id = ?", Integer.class, b);
            if (st == DonateFlow.BOX_PACKING && n > 0) {
                boxService.deliver(b, org, ADMIN);
            }
        }
        long ms2 = (System.nanoTime() - t1) / 1_000_000;
        report("装箱 + 边装边送（6 人装 + 1 人送）", 7, ms2, ops.get());
        assertTrue(unexpected.isEmpty(), "第二阶段出现意外异常：" + unexpected);
        assertEquals(120, packed.get());

        // ---- 不变式 ----
        assertEquals(40, jdbc.queryForObject("SELECT COUNT(*) FROM donate_shipment WHERE biz_id = ? AND status = ?",
                Integer.class, campaign, DonateFlow.SHIPMENT_CHECKED));
        assertEquals(40, jdbc.queryForObject("SELECT COUNT(*) FROM donate_item_trace t JOIN donate_shipment s "
                + "ON s.id = t.shipment_id WHERE s.biz_id = ? AND t.action = ?", Integer.class, campaign, DonateFlow.ACT_ARRIVE),
                "每个包裹恰好一条到货轨迹——重复扫码不得多记");
        assertEquals(120, jdbc.queryForObject("SELECT COUNT(*) FROM donate_item WHERE biz_id = ? AND status = ?",
                Integer.class, campaign, DonateFlow.ITEM_DELIVERED), "合格物资最终全部送达");
        assertEquals(40, jdbc.queryForObject("SELECT COUNT(*) FROM donate_item WHERE biz_id = ? AND status = ?",
                Integer.class, campaign, DonateFlow.ITEM_REJECTED));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM donate_item i JOIN donate_box b ON b.id = i.box_id "
                + "WHERE i.biz_id = ? AND (b.status <> ? OR i.status <> ? OR i.recipient_org_name <> b.recipient_org_name)",
                Integer.class, campaign, DonateFlow.BOX_DELIVERED, DonateFlow.ITEM_DELIVERED),
                "送达物资必须在已送达的箱子里，且单位名与箱子一致");
        assertEquals(120, jdbc.queryForObject("SELECT COUNT(*) FROM donate_item_trace t JOIN donate_item i ON i.id = t.item_id "
                + "WHERE i.biz_id = ? AND t.action = ?", Integer.class, campaign, DonateFlow.ACT_CODE), "每件一条生成码轨迹");
        assertEquals(120, jdbc.queryForObject("SELECT COUNT(*) FROM donate_item_trace t JOIN donate_item i ON i.id = t.item_id "
                + "WHERE i.biz_id = ? AND t.action = ?", Integer.class, campaign, DonateFlow.ACT_DELIVER), "每件一条送达轨迹");
        DonateFlowVOs.CampaignStats stats = campaignService.detailForAdmin(campaign).getStats();
        assertEquals(40, stats.getParticipants());
        assertEquals(40, stats.getParcels());
        // 每件数量都是 1、合格的 120 件不是书就是球，所以两类合计恰好 120；被判不合格的 40 件（不论是书是球）一件都不算
        assertEquals(120, stats.getBooks() + stats.getSports(), "统计只算合格的");
        assertEquals(40, countRejected(campaign, DonateFlow.TYPE_BOOK) + countRejected(campaign, DonateFlow.TYPE_SPORTS));
    }

    // ---------------- harness ----------------

    @FunctionalInterface
    private interface Worker {
        void run(int index) throws Exception;
    }

    /** n 个线程屏障齐发；BusinessException 以外的异常收进 unexpected，其余经 Future.get() 抛出。 */
    private static void runAll(int n, Worker body, ConcurrentLinkedQueue<Throwable> unexpected) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<Future<?>> fs = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                int idx = i;
                fs.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    try {
                        body.run(idx);
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) {
                f.get(300, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private int countRejected(Long campaign, int type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM donate_item WHERE biz_id = ? AND item_type = ? AND status = ?",
                Integer.class, campaign, type, DonateFlow.ITEM_REJECTED);
    }

    private static void report(String name, int concurrency, long ms, long count) {
        System.out.printf("[STRESS] %s：并发 %d，耗时 %d ms，计数 %d，约 %.1f 次/秒%n",
                name, concurrency, ms, count, ms == 0 ? 0.0 : count * 1000.0 / ms);
    }

    @SuppressWarnings("unused")
    private static DonateItemInputDTO unused() {
        return null;
    }
}
