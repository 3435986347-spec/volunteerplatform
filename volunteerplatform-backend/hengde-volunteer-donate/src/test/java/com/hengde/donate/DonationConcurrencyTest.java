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
import com.hengde.trade.vo.TradeVOs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
 * 捐款的并发赛跑（V3 捐款批）——<b>必跑</b>。
 *
 * <p>每一轮只断言账对得上：<b>已筹 / 已付 = 已到账捐款之和</b>，取消了的结对不留已到账的钱，
 * 捐款已取消的交易单没被付成。赛跑偏置起跑并断言每种结局都出现过（只出现一种结局的赛跑只证明了一半）。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, MallPaymentFakes.class})
class DonationConcurrencyTest {

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

    @BeforeEach
    void setUp() {
        gateway.reset();
    }

    @Test
    void crowdfundCancelRacingWithTheCallback() throws Exception {
        int cancelled = 0;
        int paid = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 30; i++) {
                Long cf = openCrowdfund();
                Long donor = volunteer();
                DonationVOs.Created c = donationService.donateToCrowdfund(cf, donor, crowdfund("25", null, false));
                long cancelDelay = i % 3 == 1 ? 25 : 0;
                long payDelay = i % 3 == 0 ? 25 : 0;
                race(pool, () -> {
                    try {
                        donationService.cancel(c.getDonation().getId(), donor);
                    } catch (BusinessException ignored) {
                        // 取消输了（刚付款）是合法结局
                    }
                    return null;
                }, cancelDelay, () -> callback(c.getPrepay()), payDelay);

                int status = donationStatus(c.getDonation().getId());
                BigDecimal raised = jdbc.queryForObject("SELECT raised_amount FROM donate_crowdfund WHERE id = ?",
                        BigDecimal.class, cf);
                if (status == DonationFlow.CANCELLED) {
                    cancelled++;
                    assertTrue(tradeStatus(c.getPrepay()) != TradeFlow.ORDER_PAID, "第 " + i + " 轮：捐款已取消而交易单已支付");
                    assertEquals(0, raised.signum(), "第 " + i + " 轮：取消了的不计入已筹");
                } else {
                    paid++;
                    assertEquals(DonationFlow.PAID, status, "第 " + i + " 轮");
                    assertEquals(TradeFlow.ORDER_PAID, tradeStatus(c.getPrepay()), "第 " + i + " 轮");
                    assertEquals(0, new BigDecimal("25").compareTo(raised), "第 " + i + " 轮：到账就计入已筹");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.println("众筹捐款 取消 vs 回调 30 轮：取消 " + cancelled + "，到账 " + paid);
        assertTrue(cancelled > 0 && paid > 0, "两种结局都要出现过");
    }

    @Test
    void pairCancelByAdminRacingWithTheCallback() throws Exception {
        int cancelledBeforePayment = 0;
        int cancelledThenRefunded = 0;
        int cancelRefused = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 30; i++) {
                Long project = openPairProject();
                Long donor = volunteer();
                PairVOs.PairRecord r = pairService.register(project, donor, register("200"));
                pairService.establish(r.getId(), ADMIN);
                DonationVOs.Created c = donationService.donateToPair(project, donor, pair());
                long cancelDelay = i % 3 == 1 ? 25 : 0;
                long payDelay = i % 3 == 0 ? 25 : 0;
                AtomicInteger refused = new AtomicInteger();
                race(pool, () -> {
                    try {
                        pairService.cancel(r.getId(), "赛跑取消", ADMIN);
                    } catch (BusinessException e) {
                        refused.incrementAndGet();
                    }
                    return null;
                }, cancelDelay, () -> callback(c.getPrepay()), payDelay);

                int recordStatus = jdbc.queryForObject("SELECT status FROM donate_pair_record WHERE id = ?",
                        Integer.class, r.getId());
                int donation = donationStatus(c.getDonation().getId());
                assertLedger(project, r.getId(), i);
                if (recordStatus == PairFlow.PAIR_CANCELLED) {
                    assertTrue(donation == DonationFlow.CANCELLED || donation == DonationFlow.REFUNDED,
                            "第 " + i + " 轮：结对取消了，钱不能留在账上（捐款状态 " + donation + "）");
                    if (donation == DonationFlow.CANCELLED) {
                        cancelledBeforePayment++;
                        assertTrue(tradeStatus(c.getPrepay()) != TradeFlow.ORDER_PAID, "第 " + i + " 轮");
                    } else {
                        cancelledThenRefunded++;
                        assertEquals(TradeFlow.ORDER_REFUNDED, tradeStatus(c.getPrepay()), "第 " + i + " 轮：原路退款");
                    }
                } else {
                    cancelRefused++;
                    assertEquals(1, refused.get(), "第 " + i + " 轮：结对没取消，只能是取消被拒了");
                    assertEquals(DonationFlow.PAID, donation, "第 " + i + " 轮：取消被拒，是因为刚付款");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.println("结对取消 vs 回调 30 轮：先取消 " + cancelledBeforePayment + "，取消后退款 "
                + cancelledThenRefunded + "，取消被拒 " + cancelRefused);
        assertTrue(cancelledBeforePayment > 0 && cancelledThenRefunded > 0,
                "「付款前取消」与「付款后取消并退款」两种结局都要出现过");
    }

    @Test
    void concurrentPairDonateClicks_leaveExactlyOneAwaitingDonation() throws Exception {
        Long project = openPairProject();
        Long donor = volunteer();
        PairVOs.PairRecord r = pairService.register(project, donor, register("300"));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                try {
                    donationService.donateToPair(project, donor, pair());
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<Boolean> f : fs) {
            if (f.get(1, TimeUnit.MINUTES)) {
                ok++;
            }
        }
        pool.shutdownNow();
        assertEquals(1, ok, "连点 6 次付款，只能落一笔待支付");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM donate_donation WHERE pair_record_id = ?",
                Integer.class, r.getId()));
    }

    // ---------------- helpers ----------------

    private void race(ExecutorService pool, Callable<Void> a, long aDelay, Callable<Void> b, long bDelay)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Future<Void> fa = pool.submit(() -> {
            start.await();
            Thread.sleep(aDelay);
            return a.call();
        });
        Future<Void> fb = pool.submit(() -> {
            start.await();
            Thread.sleep(bDelay);
            return b.call();
        });
        start.countDown();
        fa.get(1, TimeUnit.MINUTES);
        fb.get(1, TimeUnit.MINUTES);
    }

    private Void callback(TradeVOs.Prepay prepay) {
        tradeOrderService.applyPaidResult(prepay.getOutTradeNo(), "wx-" + next(), prepay.getAmountFen(),
                LocalDateTime.now().withNano(0), "openid", "{}", TradeFlow.SOURCE_CALLBACK);
        return null;
    }

    private void assertLedger(Long project, Long recordId, int round) {
        BigDecimal paidSum = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM donate_donation "
                + "WHERE pair_record_id = ? AND status = ?", BigDecimal.class, recordId, DonationFlow.PAID);
        BigDecimal recordPaid = jdbc.queryForObject("SELECT paid_amount FROM donate_pair_record WHERE id = ?",
                BigDecimal.class, recordId);
        BigDecimal projectRaised = jdbc.queryForObject("SELECT raised_amount FROM donate_pair_project WHERE id = ?",
                BigDecimal.class, project);
        assertEquals(0, paidSum.compareTo(recordPaid), "第 " + round + " 轮：结对已付 = 已到账捐款之和");
        assertEquals(0, paidSum.compareTo(projectRaised), "第 " + round + " 轮：项目已到账 = 已到账捐款之和");
    }

    private int donationStatus(Long id) {
        return jdbc.queryForObject("SELECT status FROM donate_donation WHERE id = ?", Integer.class, id);
    }

    private int tradeStatus(TradeVOs.Prepay prepay) {
        return jdbc.queryForObject("SELECT status FROM trade_order WHERE out_trade_no = ?", Integer.class,
                prepay.getOutTradeNo());
    }

    private Long openCrowdfund() {
        PairDTOs.CrowdfundSave d = new PairDTOs.CrowdfundSave();
        d.setTitle("赛跑众筹-" + next());
        d.setTargetAmount(new BigDecimal("10000"));
        Long id = crowdfundService.create(d, ADMIN);
        crowdfundService.publish(id);
        return id;
    }

    private Long openPairProject() {
        PairDTOs.ProjectSave d = new PairDTOs.ProjectSave();
        d.setTitle("赛跑结对-" + next());
        d.setProjectType(PairFlow.TYPE_STUDY);
        d.setTargetAmount(new BigDecimal("1000"));
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

    private Long volunteer() {
        return BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "赛跑捐款人", phone(), true);
    }
}
