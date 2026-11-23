package com.hengde.trade;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.dto.TradeDTOs;
import com.hengde.trade.gateway.PaymentGateway;
import com.hengde.trade.service.TradeCallbackService;
import com.hengde.trade.service.TradeOrderService;
import com.hengde.trade.service.TradeRefundService;
import com.hengde.trade.support.FakePaymentGateway;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 收付压力测试（{@code @Tag("stress")}，默认不跑；{@code -Dtest.excludedGroups=none -Dgroups=stress}）。
 *
 * <p>压的是 D2 那句话的落点：<b>回调、主动查单、扫描三条路同时把同一笔支付写回来</b>，
 * 以及并发退款。不断言「谁赢」，只断言不变量：</p>
 * <ol>
 *   <li>每张已支付的单<b>恰好一条支付流水</b>（{@code uk_transaction} 是唯一防线，不是「先查再插」）；</li>
 *   <li>已退金额<b>永不越过订单金额</b>，且等于该单下非失败退款之和；</li>
 *   <li>除业务拒绝外<b>没有任何异常</b>——死锁、500 都算失败。</li>
 * </ol>
 *
 * @author hengde
 */
@Tag("stress")
@SpringBootTest
@Import(TestcontainersConfig.class)
class TradeStressTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);
    private static final PaymentGateway.CallbackHeaders HEADERS =
            new PaymentGateway.CallbackHeaders("serial", "signature", "1700000000", "nonce");
    private static final long ADMIN = 8801L;

    private static final int ORDERS = 40;
    private static final int AMOUNT = 900;
    private static final int THREADS = 12;

    @Autowired
    private TradeOrderService orderService;
    @Autowired
    private TradeCallbackService callbackService;
    @Autowired
    private TradeRefundService refundService;
    @Autowired
    private FakePaymentGateway gateway;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    @Test
    void threePathsRacingOnTheSamePayment_leaveExactlyOneRow() throws Exception {
        List<String> outTradeNos = new ArrayList<>();
        Map<String, String> txnByOrder = new ConcurrentHashMap<>();
        for (int i = 0; i < ORDERS; i++) {
            String out = newOrder(AMOUNT);
            String txn = "wx-" + SEQ.incrementAndGet();
            outTradeNos.add(out);
            txnByOrder.put(out, txn);
            gateway.markPaidOnRemote(out, txn, AMOUNT);
        }

        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> fs = new ArrayList<>();
        for (String out : outTradeNos) {
            String txn = txnByOrder.get(out);
            // 同一笔支付，三条路同时写回来
            fs.add(pool.submit(() -> run(start, outcomes, failures, "回调",
                    () -> callbackService.handlePay(HEADERS, payBody(out, txn, AMOUNT)))));
            fs.add(pool.submit(() -> run(start, outcomes, failures, "查单",
                    () -> orderService.queryAndSync(out, TradeFlow.SOURCE_QUERY))));
            fs.add(pool.submit(() -> run(start, outcomes, failures, "扫描",
                    () -> orderService.applyPaidResult(out, txn, AMOUNT, LocalDateTime.now().withNano(0),
                            "openid", "{}", TradeFlow.SOURCE_SCAN))));
        }
        long t0 = System.currentTimeMillis();
        start.countDown();
        for (Future<?> f : fs) {
            f.get(5, TimeUnit.MINUTES);
        }
        long wall = System.currentTimeMillis() - t0;
        pool.shutdownNow();

        report("三路回写", outTradeNos.size() * 3, wall, outcomes);
        assertTrue(failures.isEmpty(), "除业务拒绝外不应有任何异常（死锁 / 500）：" + failures);
        for (String out : outTradeNos) {
            assertEquals(TradeFlow.ORDER_PAID, statusOf(out), "每张单都该被推到已支付：" + out);
            assertEquals(1, paymentCount(out),
                    "同一笔支付只能有一条流水（uk_transaction 是唯一防线）：" + out);
        }
    }

    @Test
    void concurrentRefunds_neverExceedTheOrderAmount() throws Exception {
        List<String> paid = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String out = newOrder(AMOUNT);
            orderService.applyPaidResult(out, "wx-" + SEQ.incrementAndGet(), AMOUNT,
                    LocalDateTime.now().withNano(0), "openid", "{}", TradeFlow.SOURCE_CALLBACK);
            paid.add(out);
        }

        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> fs = new ArrayList<>();
        for (String out : paid) {
            Long id = orderId(out);
            // 每单并发发起 5 笔 300 分的退款，而单子只有 900 分：最多只能成功 3 笔
            for (int i = 0; i < 5; i++) {
                fs.add(pool.submit(() -> run(start, outcomes, failures, "退款",
                        () -> refundService.refund(id, refund(300, "压测退款"), ADMIN))));
            }
        }
        long t0 = System.currentTimeMillis();
        start.countDown();
        for (Future<?> f : fs) {
            f.get(5, TimeUnit.MINUTES);
        }
        long wall = System.currentTimeMillis() - t0;
        pool.shutdownNow();

        report("并发退款", paid.size() * 5, wall, outcomes);
        assertTrue(failures.isEmpty(), "除业务拒绝外不应有任何异常（死锁 / 500）：" + failures);
        for (String out : paid) {
            int refunded = refundedOf(out);
            assertTrue(refunded <= AMOUNT, "已退金额越过了订单金额：" + out + " → " + refunded);
            Integer sum = jdbc.queryForObject("SELECT COALESCE(SUM(r.amount), 0) FROM trade_refund r "
                    + "JOIN trade_order o ON o.id = r.trade_order_id "
                    + "WHERE o.out_trade_no = ? AND r.status <> ?", Integer.class, out, TradeFlow.REFUND_FAILED);
            assertEquals(sum, refunded, "已退金额必须等于该单下非失败退款之和：" + out);
        }
    }

    // ---------- helpers ----------

    private void run(CountDownLatch start, Map<String, AtomicInteger> outcomes, List<String> failures,
                     String kind, Runnable action) {
        try {
            start.await();
            action.run();
            outcomes.computeIfAbsent(kind + "·成功", k -> new AtomicInteger()).incrementAndGet();
        } catch (BusinessException e) {
            outcomes.computeIfAbsent(kind + "·拒绝·" + e.getMessage(), k -> new AtomicInteger()).incrementAndGet();
        } catch (Exception e) {
            failures.add(kind + " → " + e);
        }
    }

    private static void report(String title, int ops, long wall, Map<String, AtomicInteger> outcomes) {
        StringBuilder sb = new StringBuilder("\n==== 收付压测·").append(title).append("：").append(ops)
                .append(" 次操作 / ").append(THREADS).append(" 线程 / ").append(wall).append(" ms（")
                .append(String.format("%.1f", ops * 1000.0 / Math.max(1, wall))).append(" ops/s）====\n");
        new TreeMap<>(outcomes).forEach((k, n) -> sb.append("  ").append(k).append(" : ").append(n.get()).append('\n'));
        System.out.println(sb);
    }

    private String newOrder(int amountFen) {
        TradeDTOs.CreateOrder d = new TradeDTOs.CreateOrder();
        d.setBizType(TradeFlow.BIZ_MALL_SHIPPING);
        d.setBizNo("stress-" + SEQ.incrementAndGet());
        d.setVolunteerId(4004L);
        d.setPayerOpenid("openid-stress");
        d.setSubject("压测商品");
        d.setAmountFen(amountFen);
        return orderService.createOrder(d).getOutTradeNo();
    }

    private static TradeDTOs.Refund refund(int amountFen, String reason) {
        TradeDTOs.Refund d = new TradeDTOs.Refund();
        d.setAmountFen(amountFen);
        d.setReason(reason);
        return d;
    }

    private static String payBody(String outTradeNo, String transactionId, int amountFen) {
        return "{\"kind\":\"PAY\",\"outTradeNo\":\"" + outTradeNo + "\",\"transactionId\":\"" + transactionId
                + "\",\"amountFen\":\"" + amountFen + "\",\"payerOpenid\":\"openid-stress\"}";
    }

    private int statusOf(String outTradeNo) {
        return jdbc.queryForObject("SELECT status FROM trade_order WHERE out_trade_no = ?",
                Integer.class, outTradeNo);
    }

    private int refundedOf(String outTradeNo) {
        return jdbc.queryForObject("SELECT refunded_amount FROM trade_order WHERE out_trade_no = ?",
                Integer.class, outTradeNo);
    }

    private Long orderId(String outTradeNo) {
        return jdbc.queryForObject("SELECT id FROM trade_order WHERE out_trade_no = ?", Long.class, outTradeNo);
    }

    private int paymentCount(String outTradeNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trade_payment p JOIN trade_order o ON o.id = p.trade_order_id "
                + "WHERE o.out_trade_no = ?", Integer.class, outTradeNo);
    }
}
