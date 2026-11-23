package com.hengde.donate;

import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.service.PointService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.constant.MallOrderStatus;
import com.hengde.donate.entity.MallOrder;
import com.hengde.donate.service.MallOrderService;
import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.service.TradeOrderService;
import com.hengde.trade.vo.TradeVOs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.hengde.donate.MallExpressOrderTest.pickup;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商城现金付款的并发赛跑（V3 商城快递批）——<b>必跑</b>，不打 stress 标签。
 *
 * <p>每一轮只断言一件事：<b>兑换单与交易单的结局必须对得上</b>——</p>
 * <ul>
 *   <li>兑换单已取消 ⇒ 交易单没有被付成（已关闭）、积分库存都已归还；</li>
 *   <li>兑换单待审核 ⇒ 交易单已支付。</li>
 * </ul>
 * <p>其余任何组合都是「钱收了、单子没了」或「单子在走、钱没收」。每种赛跑都<b>偏置起跑顺序</b>，
 * 保证两种结局都真的出现过——只出现一种结局的赛跑用例只证明了一半（trade 的关单赛跑第一版就是 30:0）。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = "hengde.donate.mall.payment.timeout-minutes=15")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, MallPaymentFakes.class})
class MallExpressConcurrencyTest {

    private static final AtomicLong SEQ = new AtomicLong(994_000_000L + System.nanoTime() % 1_000_000L);
    private static final int PRICE = 30;
    private static final int CASH = 900;

    @Autowired
    private MallOrderService orderService;
    @Autowired
    private TradeOrderService tradeOrderService;
    @Autowired
    private PointService pointService;
    @Autowired
    private MallPaymentFakes.FakeGateway gateway;
    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        gateway.reset();
    }

    @Test
    void cancelRacingWithThePaymentCallback() throws Exception {
        Outcomes outcomes = race(30, (order, prepay) -> () -> {
            try {
                orderService.cancel(order.getId(), order.getVolunteerId());
            } catch (BusinessException e) {
                // 取消输了（已付款）是合法结局之一
            }
            return null;
        });
        assertTrue(outcomes.cancelled > 0 && outcomes.paid > 0,
                "两种结局都要出现过：取消 " + outcomes.cancelled + " / 付成 " + outcomes.paid);
    }

    @Test
    void timeoutSyncRacingWithThePaymentCallback() throws Exception {
        Outcomes outcomes = race(30, (order, prepay) -> {
            // 兑换单与交易单都已过期：补偿任务要么先问到「付了」推进，要么关单取消——不能两边都赢
            jdbc.update("UPDATE mall_order SET pay_expire_time = ? WHERE id = ?",
                    LocalDateTime.now().minusMinutes(1), order.getId());
            jdbc.update("UPDATE trade_order SET expire_time = ? WHERE out_trade_no = ?",
                    LocalDateTime.now().minusMinutes(1), prepay.getOutTradeNo());
            return () -> {
                orderService.syncAwaitingPayments();
                return null;
            };
        });
        assertTrue(outcomes.cancelled > 0 && outcomes.paid > 0,
                "两种结局都要出现过：取消 " + outcomes.cancelled + " / 付成 " + outcomes.paid);
    }

    @Test
    void concurrentPayClicksShareOneTradeOrder() throws Exception {
        long volunteer = SEQ.incrementAndGet();
        givePoints(volunteer, 1000);
        MallOrder order = orderService.placeOrder(volunteer, pickup(cashSpec(1)));
        pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TradeVOs.Prepay>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int n = i;
            fs.add(pool.submit(() -> {
                start.await();
                return orderService.pay(order.getId(), volunteer, "code-" + n);
            }));
        }
        start.countDown();
        String out = null;
        for (Future<TradeVOs.Prepay> f : fs) {
            String got = f.get(1, TimeUnit.MINUTES).getOutTradeNo();
            if (out == null) {
                out = got;
            }
            assertEquals(out, got, "六次连点付款必须落到同一张交易单上");
        }
        pool.shutdownNow();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE biz_type = ? AND biz_no = ?",
                Integer.class, TradeFlow.BIZ_MALL_SHIPPING, String.valueOf(order.getId())));
    }

    // ---------------- 赛跑骨架 ----------------

    private interface Contender {
        Callable<Void> prepare(MallOrder order, TradeVOs.Prepay prepay) throws Exception;
    }

    private record Outcomes(int cancelled, int paid) {
    }

    private Outcomes race(int rounds, Contender contender) throws Exception {
        int cancelled = 0;
        int paid = 0;
        pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                long volunteer = SEQ.incrementAndGet();
                givePoints(volunteer, 1000);
                long spec = cashSpec(3);
                MallOrder order = orderService.placeOrder(volunteer, pickup(spec));
                TradeVOs.Prepay prepay = orderService.pay(order.getId(), volunteer, "c");
                Callable<Void> other = contender.prepare(order, prepay);
                String txn = "wx-" + SEQ.incrementAndGet();

                long otherDelay = i % 3 == 1 ? 25 : 0;
                long payDelay = i % 3 == 0 ? 25 : 0;
                CountDownLatch start = new CountDownLatch(1);
                Future<Void> f1 = pool.submit(() -> {
                    start.await();
                    Thread.sleep(otherDelay);
                    return other.call();
                });
                Future<Boolean> f2 = pool.submit(() -> {
                    start.await();
                    Thread.sleep(payDelay);
                    return tradeOrderService.applyPaidResult(prepay.getOutTradeNo(), txn, CASH,
                            LocalDateTime.now().withNano(0), "openid", "{}", TradeFlow.SOURCE_CALLBACK);
                });
                start.countDown();
                f1.get(1, TimeUnit.MINUTES);
                f2.get(1, TimeUnit.MINUTES);

                int orderStatus = jdbc.queryForObject("SELECT status FROM mall_order WHERE id = ?", Integer.class,
                        order.getId());
                int tradeStatus = jdbc.queryForObject("SELECT status FROM trade_order WHERE out_trade_no = ?",
                        Integer.class, prepay.getOutTradeNo());
                int stock = jdbc.queryForObject("SELECT stock FROM mall_goods_spec WHERE id = ?", Integer.class, spec);
                if (orderStatus == MallOrderStatus.CANCELLED) {
                    cancelled++;
                    assertTrue(tradeStatus != TradeFlow.ORDER_PAID,
                            "第 " + i + " 轮：兑换单已取消而交易单已支付——钱收了、单子没了");
                    assertEquals(1000, pointService.balanceOf(volunteer), "第 " + i + " 轮：取消了就要退分");
                    assertEquals(3, stock, "第 " + i + " 轮：取消了就要还库存");
                } else {
                    paid++;
                    assertEquals(MallOrderStatus.PENDING, orderStatus, "第 " + i + " 轮：没取消就必须已推进到待审核");
                    assertEquals(TradeFlow.ORDER_PAID, tradeStatus, "第 " + i + " 轮：推进到待审核就必须真的付了");
                    assertEquals(1000 - PRICE, pointService.balanceOf(volunteer));
                    assertEquals(2, stock);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.println("商城付款赛跑 " + rounds + " 轮：取消 " + cancelled + "，付成 " + paid);
        return new Outcomes(cancelled, paid);
    }

    private long cashSpec(int stock) {
        long goodsId = SEQ.incrementAndGet();
        long specId = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO mall_goods (id, name, status, hidden, sort, create_time, update_time, is_deleted) "
                + "VALUES (?, '赛跑用例商品', ?, 0, 0, NOW(), NOW(), 0)", goodsId, MallGoodsStatus.ON_SALE);
        jdbc.update("INSERT INTO mall_goods_spec (id, goods_id, name, points, cash_fen, stock, sort, create_time, "
                + "update_time, is_deleted) VALUES (?, ?, '默认', ?, ?, ?, 0, NOW(), NOW(), 0)",
                specId, goodsId, PRICE, CASH, stock);
        return specId;
    }

    private void givePoints(long volunteerId, int amount) {
        jdbc.update("INSERT INTO point_record (volunteer_id, change_amount, source_type, source_id, remark, "
                + "operator_type, create_time, update_time, is_deleted) VALUES (?, ?, ?, NULL, '用例预置', 0, NOW(), NOW(), 0)",
                volunteerId, amount, PointSourceType.MANUAL);
    }
}
