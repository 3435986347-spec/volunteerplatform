package com.hengde.trade;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.dto.TradeDTOs;
import com.hengde.trade.entity.TradeOrder;
import com.hengde.trade.service.TradeOrderService;
import com.hengde.trade.support.FakePaymentGateway;
import com.hengde.trade.vo.TradeVOs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 给业务域用的三个入口（商城快递批起）：付款截止、按业务单查交易单、业务侧关单。
 *
 * <p>最承重的是 {@link TradeOrderService#closeByBiz}：用户取消待支付的兑换单时，<b>同一刻回调可能正把交易单推成已支付</b>。
 * 两者必须恰好一方赢——关单赢了，这笔钱就进不来（或进来成「已关闭收到支付」、被对账列出）；
 * 付款赢了，关单必须返回 false，让业务侧放弃取消。<b>两边都以为自己赢了，就是钱收了、单子没了。</b></p>
 *
 * <p><b>需本机 Docker</b>（MySQL）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class TradeBizEntryTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);

    @Autowired
    private TradeOrderService orderService;
    @Autowired
    private FakePaymentGateway gateway;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    @Test
    void expireAt_capsTheOrderLifetime_andAPassedDeadlineIsRefused() {
        LocalDateTime deadline = LocalDateTime.now().plusMinutes(4).withNano(0);
        TradeDTOs.CreateOrder d = dto("cap-" + SEQ.incrementAndGet(), 500);
        d.setExpireAt(deadline);
        TradeVOs.Prepay p = orderService.createOrder(d);
        assertEquals(deadline, p.getExpireTime(), "业务单只剩 4 分钟，交易单就不能给满 15 分钟");

        TradeDTOs.CreateOrder late = dto("late-" + SEQ.incrementAndGet(), 500);
        late.setExpireAt(LocalDateTime.now().minusSeconds(1));
        assertThrows(BusinessException.class, () -> orderService.createOrder(late));

        TradeDTOs.CreateOrder far = dto("far-" + SEQ.incrementAndGet(), 500);
        far.setExpireAt(LocalDateTime.now().plusDays(2));
        TradeVOs.Prepay pf = orderService.createOrder(far);
        assertTrue(pf.getExpireTime().isBefore(LocalDateTime.now().plusMinutes(16)), "截止更晚时仍按 TTL");
    }

    @Test
    void findLatestByBiz_seesClosedOrdersToo() {
        String bizNo = "latest-" + SEQ.incrementAndGet();
        assertNull(orderService.findLatestByBiz(TradeFlow.BIZ_MALL_SHIPPING, bizNo));
        String first = orderService.createOrder(dto(bizNo, 300)).getOutTradeNo();
        assertTrue(orderService.closeByBiz(TradeFlow.BIZ_MALL_SHIPPING, bizNo));
        TradeOrder closed = orderService.findLatestByBiz(TradeFlow.BIZ_MALL_SHIPPING, bizNo);
        assertEquals(first, closed.getOutTradeNo());
        assertEquals(TradeFlow.ORDER_CLOSED, closed.getStatus(), "已关闭的也要查得到——补偿任务据它决定取消业务单");
        assertTrue(gateway.closedOrders().contains(first), "渠道侧也要关，否则用户仍能付进来");

        String second = orderService.createOrder(dto(bizNo, 300)).getOutTradeNo();
        assertEquals(second, orderService.findLatestByBiz(TradeFlow.BIZ_MALL_SHIPPING, bizNo).getOutTradeNo(),
                "关单后重下，最近的是新单");
        assertTrue(orderService.closeByBiz(TradeFlow.BIZ_MALL_SHIPPING, "nothing-" + SEQ.incrementAndGet()),
                "没有活单就是没什么可关，算成功");
    }

    @Test
    void closeByBiz_refusesOnceTheOrderIsPaid() {
        String bizNo = "paid-" + SEQ.incrementAndGet();
        String out = orderService.createOrder(dto(bizNo, 700)).getOutTradeNo();
        assertTrue(orderService.applyPaidResult(out, "wx-" + SEQ.incrementAndGet(), 700,
                LocalDateTime.now().withNano(0), "openid", "{}", TradeFlow.SOURCE_CALLBACK));

        assertFalse(orderService.closeByBiz(TradeFlow.BIZ_MALL_SHIPPING, bizNo), "已支付的关不掉，业务侧必须放弃取消");
        assertEquals(TradeFlow.ORDER_PAID, statusOf(out));
    }

    @Test
    void closeRacingWithPayment_exactlyOneSideWins() throws Exception {
        int rounds = 30;
        int bothWon = 0;
        int closedWins = 0;
        int paidWins = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                String bizNo = "race-" + SEQ.incrementAndGet();
                String out = orderService.createOrder(dto(bizNo, 900)).getOutTradeNo();
                String txn = "wx-" + SEQ.incrementAndGet();
                CountDownLatch start = new CountDownLatch(1);
                // 三分之一让关单先走、三分之一让付款先走、三分之一同时——不偏置的话付款几乎总是赢
                // （它第一条就是加锁读，关单第一条是普通读），「关单赢」那条分支就一次都没被跑到
                long closeDelay = i % 3 == 1 ? 15 : 0;
                long payDelay = i % 3 == 0 ? 15 : 0;
                Future<Boolean> close = pool.submit(() -> {
                    start.await();
                    Thread.sleep(closeDelay);
                    return orderService.closeByBiz(TradeFlow.BIZ_MALL_SHIPPING, bizNo);
                });
                Future<Boolean> pay = pool.submit(() -> {
                    start.await();
                    Thread.sleep(payDelay);
                    return orderService.applyPaidResult(out, txn, 900, LocalDateTime.now().withNano(0),
                            "openid", "{}", TradeFlow.SOURCE_CALLBACK);
                });
                start.countDown();
                boolean closedOk = close.get(1, TimeUnit.MINUTES);
                boolean paidMoved = pay.get(1, TimeUnit.MINUTES);
                int status = statusOf(out);
                if (closedOk && paidMoved) {
                    bothWon++;
                }
                if (closedOk) {
                    closedWins++;
                    assertEquals(TradeFlow.ORDER_CLOSED, status, "关单报了成功，单子就必须是已关闭");
                } else {
                    paidWins++;
                    assertEquals(TradeFlow.ORDER_PAID, status, "关单报了失败，只能是因为已经付了");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.println("关单对付款赛跑 " + rounds + " 轮：关单赢 " + closedWins + "，付款赢 " + paidWins);
        assertEquals(0, bothWon, "两边都以为自己赢了＝钱收了、单子没了");
        assertTrue(closedWins > 0 && paidWins > 0, "两种结局都要真的出现过，否则这条用例只证明了一半");
    }

    // ---------- helpers ----------

    private static TradeDTOs.CreateOrder dto(String bizNo, int amountFen) {
        TradeDTOs.CreateOrder d = new TradeDTOs.CreateOrder();
        d.setBizType(TradeFlow.BIZ_MALL_SHIPPING);
        d.setBizNo(bizNo);
        d.setVolunteerId(1201L);
        d.setPayerOpenid("openid-" + bizNo);
        d.setSubject("业务入口用例");
        d.setAmountFen(amountFen);
        return d;
    }

    private int statusOf(String outTradeNo) {
        return jdbc.queryForObject("SELECT status FROM trade_order WHERE out_trade_no = ?", Integer.class, outTradeNo);
    }
}
