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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.hengde.donate.BookDonationTestSupport.ADMIN;
import static com.hengde.donate.BookDonationTestSupport.expressNo;
import static com.hengde.donate.BookDonationTestSupport.item;
import static com.hengde.donate.BookDonationTestSupport.phone;
import static com.hengde.donate.BookDonationTestSupport.result;
import static com.hengde.donate.BookDonationTestSupport.shipment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扫码动作的并发正确性——<b>必跑</b>（不打 stress 标签）。仓库里多台手机同时扫码是常态：
 * 同一件东西被两个人扫进两只箱子、有人还在往箱子里装而另一个人已经点了「送达」、同一个包裹被连扫两次。
 *
 * <p>结果一律 {@code Future.get()} 收；<b>只有 {@link BusinessException} 是可接受的失败</b>——
 * 死锁、唯一键冲突漏网都会原样抛出使用例变红。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class DonateBoxConcurrencyTest {

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

    /** 同一件物资，8 个线程同时往两只箱子里装 → 恰好一次成功，物资只在其中一只箱子里。 */
    @Test
    void oneItemCanOnlyEndUpInOneBox() throws Exception {
        Long campaign = BookDonationTestSupport.openCampaign(campaignService);
        List<String> codes = qualifiedCodes(campaign, 1);
        DonateFlowVOs.Box a = boxService.create(campaign, ADMIN);
        DonateFlowVOs.Box b = boxService.create(campaign, ADMIN);

        List<Boolean> ok = race(8, i -> {
            boxService.pack(i % 2 == 0 ? a.getId() : b.getId(), codes.get(0), ADMIN);
            return true;
        });

        assertEquals(1, ok.stream().filter(x -> x).count(), "一件物资只能装进一只箱子一次");
        Long boxId = jdbc.queryForObject("SELECT box_id FROM donate_item WHERE exclusive_code = ?", Long.class, codes.get(0));
        assertTrue(boxId.equals(a.getId()) || boxId.equals(b.getId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM donate_item_trace t JOIN donate_item i ON i.id = t.item_id "
                        + "WHERE i.exclusive_code = ? AND t.action = ?", Integer.class, codes.get(0), DonateFlow.ACT_PACK),
                "装箱轨迹只能有一条");
    }

    /**
     * 装箱与送达赛跑：12 件物资由 12 个线程往一只箱子里装，同时一个线程点「送达」。
     *
     * <p>不变式：<b>不存在「装在已送达箱子里却不是已送达」的物资</b>；每件物资要么随这趟送达了（已送达 + 单位名），
     * 要么没赶上（仍是合格、不在任何箱子里）。装箱的 CAS 把「箱子仍在装箱中」折进了 WHERE，
     * 送达的 CAS 先改箱子再改物资——两者在箱子行上串行化，所以不会有中间态漏网。</p>
     */
    @Test
    void packingRacingDeliveryNeverLeavesAnItemInLimbo() throws Exception {
        // 跑 5 轮：这条赛跑第一次就是在这里撞出「先物后箱 vs 先箱后物」的死锁，
        // 一轮的撞中概率随调度浮动，多跑几轮让加锁顺序一旦被改回去就稳定变红
        for (int round = 0; round < 5; round++) {
            packRacingDeliverOnce();
        }
    }

    private void packRacingDeliverOnce() throws Exception {
        Long campaign = BookDonationTestSupport.openCampaign(campaignService);
        List<String> codes = qualifiedCodes(campaign, 12);
        DonateFlowVOs.Box box = boxService.create(campaign, ADMIN);
        boxService.pack(box.getId(), codes.get(0), ADMIN);   // 先装一件，保证送达时不是空箱
        Long org = BookDonationTestSupport.org(masterDataService);

        List<Boolean> outcomes = race(12, i -> {
            if (i == 11) {
                boxService.deliver(box.getId(), org, ADMIN);
            } else {
                boxService.pack(box.getId(), codes.get(i + 1), ADMIN);
            }
            return true;
        });
        assertTrue(outcomes.get(11), "送达本身必须成功（箱里至少有预先装好的那一件）");

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM donate_item i JOIN donate_box b ON b.id = i.box_id "
                        + "WHERE b.id = ? AND b.status = ? AND i.status <> ?",
                Integer.class, box.getId(), DonateFlow.BOX_DELIVERED, DonateFlow.ITEM_DELIVERED),
                "已送达的箱子里不得有未送达的物资");
        int delivered = jdbc.queryForObject("SELECT COUNT(*) FROM donate_item WHERE box_id = ? AND status = ?",
                Integer.class, box.getId(), DonateFlow.ITEM_DELIVERED);
        int leftOut = jdbc.queryForObject("SELECT COUNT(*) FROM donate_item WHERE exclusive_code IN ("
                        + String.join(",", codes.stream().map(c -> "'" + c + "'").toList())
                        + ") AND box_id IS NULL AND status = ?", Integer.class, DonateFlow.ITEM_QUALIFIED);
        assertEquals(12, delivered + leftOut, "每件物资要么送达、要么没赶上且原样在库，不能卡在中间");
        assertEquals(delivered, jdbc.queryForObject(
                "SELECT COUNT(*) FROM donate_item_trace WHERE box_id = ? AND action = ?",
                Integer.class, box.getId(), DonateFlow.ACT_DELIVER), "送达轨迹与送达物资一一对应");
    }

    /** 同一个包裹连扫 8 次到货 / 8 次核对 → 各只成功一次，物资状态不被重复推进。 */
    @Test
    void repeatedScansOfTheSameParcelTakeEffectOnce() throws Exception {
        Long campaign = BookDonationTestSupport.openCampaign(campaignService);
        Long donor = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "并发捐赠人", phone(), true);
        DonateFlowVOs.Shipment s = shipmentService.register(donor, campaign, shipment(expressNo(),
                item("书一", DonateFlow.TYPE_BOOK, 1, null), item("书二", DonateFlow.TYPE_BOOK, 1, null)));

        List<Boolean> arrivals = race(8, i -> {
            shipmentService.arrive(s.getId(), ADMIN);
            return true;
        });
        assertEquals(1, arrivals.stream().filter(x -> x).count());

        ShipmentCheckDTO dto = BookDonationTestSupport.check(result(s.getItems().get(0).getId(), true, null),
                result(s.getItems().get(1).getId(), true, null));
        List<Boolean> checks = race(8, i -> {
            shipmentService.check(s.getId(), dto, ADMIN);
            return true;
        });
        assertEquals(1, checks.stream().filter(x -> x).count());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM donate_item_trace WHERE shipment_id = ? AND action = ?",
                Integer.class, s.getId(), DonateFlow.ACT_ARRIVE), "到货轨迹只能一条");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM donate_item_trace WHERE shipment_id = ? AND action = ?",
                Integer.class, s.getId(), DonateFlow.ACT_CHECK_PASS), "每件物资的核对轨迹只能一条");
    }

    /** 同一件物资 8 个线程同时点「生成专属码」→ 库里只有一个码，所有调用拿到的都是它。 */
    @Test
    void concurrentCodeGenerationYieldsOneCode() throws Exception {
        Long campaign = BookDonationTestSupport.openCampaign(campaignService);
        Long donor = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "并发捐赠人", phone(), true);
        DonateFlowVOs.Shipment s = shipmentService.register(donor, campaign,
                shipment(expressNo(), item("书", DonateFlow.TYPE_BOOK, 1, null)));
        shipmentService.arrive(s.getId(), ADMIN);
        Long itemId = s.getItems().get(0).getId();
        shipmentService.check(s.getId(), BookDonationTestSupport.check(result(itemId, true, null)), ADMIN);

        List<String> codes = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CyclicBarrier barrier = new CyclicBarrier(8);
        List<Future<String>> fs = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                fs.add(pool.submit(() -> {
                    barrier.await(20, TimeUnit.SECONDS);
                    return itemService.generateCode(itemId, ADMIN).getExclusiveCode();
                }));
            }
            for (Future<String> f : fs) {
                codes.add(f.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        String stored = jdbc.queryForObject("SELECT exclusive_code FROM donate_item WHERE id = ?", String.class, itemId);
        assertTrue(codes.stream().allMatch(stored::equals), "所有调用都要拿到同一个码：" + codes);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM donate_item_trace WHERE item_id = ? AND action = ?",
                Integer.class, itemId, DonateFlow.ACT_CODE), "生成码的轨迹只能一条");
    }

    // ---------------- helpers ----------------

    /** 造 n 件已核对合格并生成了专属码的物资，返回码。 */
    private List<String> qualifiedCodes(Long campaign, int n) {
        Long donor = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "装箱用例", phone(), true);
        DonateItemInputDTO[] items = new DonateItemInputDTO[n];
        for (int i = 0; i < n; i++) {
            items[i] = item("物资" + i, DonateFlow.TYPE_BOOK, 1, null);
        }
        DonateFlowVOs.Shipment s = shipmentService.register(donor, campaign, shipment(expressNo(), items));
        shipmentService.arrive(s.getId(), ADMIN);
        ShipmentCheckDTO.ItemResult[] rs = s.getItems().stream().map(i -> result(i.getId(), true, null))
                .toArray(ShipmentCheckDTO.ItemResult[]::new);
        shipmentService.check(s.getId(), BookDonationTestSupport.check(rs), ADMIN);
        List<String> codes = new ArrayList<>();
        for (DonateFlowVOs.Item i : s.getItems()) {
            codes.add(itemService.generateCode(i.getId(), ADMIN).getExclusiveCode());
        }
        return codes;
    }

    @FunctionalInterface
    private interface Indexed {
        boolean run(int i) throws Exception;
    }

    /** 屏障齐发；业务拒绝记 false，其余异常经 Future.get() 抛出使用例变红。 */
    private static List<Boolean> race(int n, Indexed body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<Future<Boolean>> fs = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                int idx = i;
                Callable<Boolean> c = () -> {
                    barrier.await(20, TimeUnit.SECONDS);
                    try {
                        return body.run(idx);
                    } catch (BusinessException rejected) {
                        return false;
                    }
                };
                fs.add(pool.submit(c));
            }
            List<Boolean> out = new ArrayList<>();
            for (Future<Boolean> f : fs) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
