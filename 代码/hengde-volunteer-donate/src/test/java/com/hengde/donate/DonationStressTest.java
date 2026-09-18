package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.DonationFlow;
import com.hengde.donate.constant.PairFlow;
import com.hengde.donate.dto.PairDTOs;
import com.hengde.donate.service.CrowdfundService;
import com.hengde.donate.service.DonationService;
import com.hengde.donate.service.PairProjectService;
import com.hengde.donate.service.PairService;
import com.hengde.donate.vo.DonationVOs;
import com.hengde.donate.vo.PairVOs;
import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.service.TradeOrderService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
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

import static com.hengde.donate.BookDonationTestSupport.ADMIN;
import static com.hengde.donate.BookDonationTestSupport.next;
import static com.hengde.donate.BookDonationTestSupport.phone;
import static com.hengde.donate.DonationServiceTest.crowdfund;
import static com.hengde.donate.DonationServiceTest.pair;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 捐款压测（{@code @Tag("stress")}；{@code -Dtest.excludedGroups=none -Dgroups=stress}）。
 *
 * <p>两个众筹项目 + 两个结对项目，40 个捐款人：先并发发起众筹捐款与结对捐款，再 16 线程乱序施加
 * 付款回调（含重投）、本人取消、超时同步、后台退款、协会取消结对，最后把剩下的待支付全部过期再同步收尾。
 * 不断言谁赢，只断言账：</p>
 * <ol>
 *   <li>没有捐款停在待支付；</li>
 *   <li><b>众筹已筹 = 已到账众筹捐款之和</b>；<b>结对项目已到账 = 已到账结对捐款之和</b>；<b>每条结对已付 = 它的已到账捐款之和</b>；</li>
 *   <li>取消了的结对不留已到账的钱；已取消的捐款交易单没被付成；已退款的交易单是已退款；</li>
 *   <li>除业务拒绝外没有任何异常（死锁也算）。</li>
 * </ol>
 *
 * @author hengde
 */
@Tag("stress")
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, MallPaymentFakes.class})
class DonationStressTest {

    private static final int DONORS = 40;
    private static final int THREADS = 16;

    @Autowired
    private DonationService donationService;
    @Autowired
    private CrowdfundService crowdfundService;
    @Autowired
    private PairProjectService projectService;
    @Autowired
    private PairService pairService;
    @Autowired
    private TradeOrderService tradeOrderService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private MallPaymentFakes.FakeGateway gateway;
    @Autowired
    private JdbcTemplate jdbc;

    private final Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
    private final List<String> failures = Collections.synchronizedList(new ArrayList<>());

    @Test
    void chaosOverDonations_keepsEveryLedgerBalanced() throws Exception {
        gateway.reset();
        List<Long> crowdfunds = List.of(openCrowdfund(), openCrowdfund());
        List<Long> projects = List.of(openPairProject(), openPairProject());
        List<Long> donors = new ArrayList<>();
        List<Long> records = Collections.synchronizedList(new ArrayList<>());
        Random rnd = new Random(917L);
        for (int i = 0; i < DONORS; i++) {
            Long v = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "压测捐款人" + i, phone(), true);
            donors.add(v);
            if (i % 2 == 0) {
                Long project = projects.get(i % projects.size());
                PairVOs.PairRecord r = pairService.register(project, v, register(String.valueOf(10 + rnd.nextInt(40))));
                if (rnd.nextBoolean()) {
                    pairService.establish(r.getId(), ADMIN);
                }
                records.add(r.getId());
            }
        }

        // ---- 第一段：并发发起 ----
        List<DonationVOs.Created> created = Collections.synchronizedList(new ArrayList<>());
        List<Runnable> starts = new ArrayList<>();
        for (int i = 0; i < DONORS; i++) {
            Long v = donors.get(i);
            Long cf = crowdfunds.get(i % crowdfunds.size());
            starts.add(() -> track("众筹发起", () -> created.add(donationService.donateToCrowdfund(cf, v,
                    crowdfund(String.valueOf(1 + (v % 50)), null, v % 3 == 0)))));
            starts.add(() -> track("众筹发起", () -> created.add(donationService.donateToCrowdfund(cf, v,
                    crowdfund("7.77", null, false)))));
            if (i % 2 == 0) {
                Long project = projects.get(i % projects.size());
                starts.add(() -> track("结对发起", () -> created.add(donationService.donateToPair(project, v, pair()))));
                starts.add(() -> track("结对发起·连点", () -> created.add(donationService.donateToPair(project, v, pair()))));
            }
        }
        long t0 = System.currentTimeMillis();
        runConcurrently(starts);
        long startWall = System.currentTimeMillis() - t0;

        // ---- 第二段：乱序施加 ----
        List<Runnable> chaos = new ArrayList<>();
        for (DonationVOs.Created c : new ArrayList<>(created)) {
            Long id = c.getDonation().getId();
            Long owner = jdbc.queryForObject("SELECT volunteer_id FROM donate_donation WHERE id = ?", Long.class, id);
            int roll = rnd.nextInt(10);
            Runnable callback = () -> track("付款回调", () -> tradeOrderService.applyPaidResult(
                    c.getPrepay().getOutTradeNo(), "wx-" + c.getPrepay().getOutTradeNo(), c.getPrepay().getAmountFen(),
                    LocalDateTime.now().withNano(0), "openid", "{}", TradeFlow.SOURCE_CALLBACK));
            if (roll < 4) {
                chaos.add(callback);
                chaos.add(callback);
            } else if (roll < 6) {
                chaos.add(callback);
                chaos.add(() -> track("本人取消", () -> donationService.cancel(id, owner)));
            } else if (roll < 8) {
                expire(id, c.getPrepay().getOutTradeNo());
                chaos.add(callback);
                chaos.add(() -> track("超时同步", donationService::syncAwaitingDonations));
            } else {
                chaos.add(callback);
                chaos.add(() -> track("后台退款", () -> donationService.refund(id, "压测退款", ADMIN)));
            }
        }
        for (Long recordId : new ArrayList<>(records)) {
            if (rnd.nextInt(3) == 0) {
                chaos.add(() -> track("协会取消结对", () -> pairService.cancel(recordId, "压测取消", ADMIN)));
            }
        }
        Collections.shuffle(chaos, rnd);
        t0 = System.currentTimeMillis();
        runConcurrently(chaos);
        long chaosWall = System.currentTimeMillis() - t0;

        // ---- 收尾：剩下的待支付全部过期再同步 ----
        jdbc.update("UPDATE donate_donation SET pay_expire_time = ? WHERE status = 0 AND volunteer_id IN ("
                + String.join(",", donors.stream().map(String::valueOf).toList()) + ")", LocalDateTime.now().minusMinutes(1));
        donationService.syncAwaitingDonations();

        System.out.println("\n==== 捐款压测：发起 " + starts.size() + " 次 " + startWall + " ms；乱序动作 " + chaos.size()
                + " 次 / " + THREADS + " 线程 / " + chaosWall + " ms ====\n  " + new TreeMap<>(outcomes));

        assertTrue(failures.isEmpty(), "除业务拒绝外不应有任何异常（死锁也算）：" + failures);
        String in = String.join(",", donors.stream().map(String::valueOf).toList());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM donate_donation WHERE status = 0 AND volunteer_id IN ("
                + in + ")", Integer.class), "不应有捐款停在待支付");
        for (Long cf : crowdfunds) {
            assertEquals(0, sumPaid("biz_type = 2 AND project_id = " + cf).compareTo(
                    jdbc.queryForObject("SELECT raised_amount FROM donate_crowdfund WHERE id = ?", BigDecimal.class, cf)),
                    "众筹已筹 = 已到账捐款之和：" + cf);
        }
        for (Long p : projects) {
            assertEquals(0, sumPaid("biz_type = 3 AND project_id = " + p).compareTo(
                    jdbc.queryForObject("SELECT raised_amount FROM donate_pair_project WHERE id = ?", BigDecimal.class, p)),
                    "结对项目已到账 = 已到账捐款之和：" + p);
        }
        for (Long r : records) {
            BigDecimal paid = sumPaid("pair_record_id = " + r);
            assertEquals(0, paid.compareTo(jdbc.queryForObject("SELECT paid_amount FROM donate_pair_record WHERE id = ?",
                    BigDecimal.class, r)), "结对已付 = 已到账捐款之和：" + r);
            int status = jdbc.queryForObject("SELECT status FROM donate_pair_record WHERE id = ?", Integer.class, r);
            if (status == PairFlow.PAIR_CANCELLED) {
                assertEquals(0, paid.signum(), "取消了的结对不留已到账的钱：" + r);
            }
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT d.status AS ds, t.status AS ts FROM donate_donation d "
                + "JOIN trade_order t ON t.biz_type = d.biz_type AND t.biz_no = CAST(d.id AS CHAR) "
                + "WHERE d.volunteer_id IN (" + in + ")")) {
            int ds = ((Number) row.get("ds")).intValue();
            int ts = ((Number) row.get("ts")).intValue();
            if (ds == DonationFlow.CANCELLED) {
                assertTrue(ts != TradeFlow.ORDER_PAID, "已取消的捐款，交易单不能是已支付");
            } else if (ds == DonationFlow.PAID) {
                assertEquals(TradeFlow.ORDER_PAID, ts, "已到账的捐款，交易单必须是已支付");
            } else if (ds == DonationFlow.REFUNDED) {
                assertEquals(TradeFlow.ORDER_REFUNDED, ts, "已退款的捐款，交易单必须是已退款");
            }
        }
    }

    // ---------------- helpers ----------------

    private BigDecimal sumPaid(String where) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM donate_donation WHERE status = 1 AND " + where,
                BigDecimal.class);
    }

    private void expire(Long donationId, String outTradeNo) {
        jdbc.update("UPDATE donate_donation SET pay_expire_time = ? WHERE id = ?", LocalDateTime.now().minusMinutes(1),
                donationId);
        jdbc.update("UPDATE trade_order SET expire_time = ? WHERE out_trade_no = ?", LocalDateTime.now().minusMinutes(1),
                outTradeNo);
    }

    private void track(String kind, Runnable action) {
        try {
            action.run();
            outcomes.computeIfAbsent(kind + "·成功", k -> new AtomicInteger()).incrementAndGet();
        } catch (BusinessException e) {
            outcomes.computeIfAbsent(kind + "·拒绝·" + e.getMessage(), k -> new AtomicInteger()).incrementAndGet();
        } catch (Exception e) {
            failures.add(kind + " → " + e);
        }
    }

    private void runConcurrently(List<Runnable> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> fs = new ArrayList<>();
        for (Runnable r : tasks) {
            fs.add(pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                r.run();
            }));
        }
        start.countDown();
        for (Future<?> f : fs) {
            f.get(5, TimeUnit.MINUTES);
        }
        pool.shutdownNow();
    }

    private Long openCrowdfund() {
        PairDTOs.CrowdfundSave d = new PairDTOs.CrowdfundSave();
        d.setTitle("压测众筹-" + next());
        d.setTargetAmount(new BigDecimal("100000"));
        Long id = crowdfundService.create(d, ADMIN);
        crowdfundService.publish(id);
        return id;
    }

    private Long openPairProject() {
        PairDTOs.ProjectSave d = new PairDTOs.ProjectSave();
        d.setTitle("压测结对-" + next());
        d.setProjectType(PairFlow.TYPE_STUDY);
        d.setTargetAmount(new BigDecimal("100000"));
        Long id = projectService.create(d, ADMIN);
        projectService.publish(id);
        return id;
    }

    private static PairDTOs.Register register(String amount) {
        PairDTOs.Register d = new PairDTOs.Register();
        d.setAmountType(PairFlow.AMOUNT_PARTIAL);
        d.setAmount(new BigDecimal(amount));
        return d;
    }
}
