package com.hengde.donate;

import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.service.PointService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.constant.MallOrderStatus;
import com.hengde.donate.constant.MallShippingPayType;
import com.hengde.donate.dto.MallOrderPlaceDTO;
import com.hengde.donate.entity.MallOrder;
import com.hengde.donate.service.MallOrderService;
import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.service.TradeOrderService;
import com.hengde.trade.vo.TradeVOs;
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
import java.util.concurrent.atomic.AtomicLong;

import static com.hengde.donate.MallExpressOrderTest.express;
import static com.hengde.donate.MallExpressOrderTest.pickup;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商城现金与快递压测（{@code @Tag("stress")}；{@code -Dtest.excludedGroups=none -Dgroups=stress}）。
 *
 * <p>三段：40 人同时下单（纯积分 / 积分 + 现金、自提 / 快递现金 / 快递积分混着来）→ 16 线程对所有单子乱序施加
 * 付款回调、取消、超时同步、审核通过、驳回 → 最后把剩下的待支付全部过期再同步一次收尾。不断言谁赢，只断言四本账：</p>
 * <ol>
 *   <li><b>没有单子停在待支付</b>；</li>
 *   <li><b>钱与单对得上</b>：已取消 / 未付款就被驳回的，交易单一定没付成；有现金且在走流程的，交易单一定已付；
 *       付过款又被驳回的，交易单一定已退款；</li>
 *   <li><b>库存守恒</b>：每个规格 初始 − 仍在占用的单数 = 现存；</li>
 *   <li><b>积分守恒</b>：每个人 初始 − 仍在占用的单的实际扣分之和 = 余额；</li>
 * </ol>
 * <p>外加：除业务拒绝外<b>没有任何异常</b>（死锁也算）。</p>
 *
 * @author hengde
 */
@Tag("stress")
@SpringBootTest(properties = {
        "hengde.donate.mall.express.enabled=true",
        "hengde.donate.mall.express.fee-fen=600",
        "hengde.donate.mall.express.points-per-yuan=10",
        "hengde.donate.mall.payment.timeout-minutes=15"})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, MallPaymentFakes.class})
class MallExpressStressTest {

    private static final AtomicLong SEQ = new AtomicLong(995_000_000L + System.nanoTime() % 1_000_000L);
    private static final int VOLUNTEERS = 40;
    private static final int ORDERS_EACH = 3;
    private static final int INITIAL_POINTS = 2000;
    private static final int INITIAL_STOCK = 40;
    private static final int THREADS = 16;
    private static final long ADMIN = 8801L;

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

    private final Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
    private final List<String> failures = Collections.synchronizedList(new ArrayList<>());

    @Test
    void chaosOverCashAndExpressOrders_keepsEveryLedgerBalanced() throws Exception {
        gateway.reset();
        long[] specs = {spec(0), spec(500), spec(1200)};
        List<Long> volunteers = new ArrayList<>();
        for (int i = 0; i < VOLUNTEERS; i++) {
            long v = SEQ.incrementAndGet();
            givePoints(v, INITIAL_POINTS);
            volunteers.add(v);
        }

        // ---- 第一段：同时下单 ----
        Random seedRnd = new Random(20260917L);
        List<Runnable> placements = new ArrayList<>();
        for (Long v : volunteers) {
            for (int k = 0; k < ORDERS_EACH; k++) {
                long spec = specs[seedRnd.nextInt(specs.length)];
                int mode = seedRnd.nextInt(3);
                placements.add(() -> track("下单", () -> {
                    MallOrderPlaceDTO dto = switch (mode) {
                        case 0 -> pickup(spec);
                        case 1 -> express(spec, MallShippingPayType.CASH);
                        default -> express(spec, MallShippingPayType.POINTS);
                    };
                    orderService.placeOrder(v, dto);
                }));
            }
        }
        long t0 = System.currentTimeMillis();
        runConcurrently(placements);
        long placeWall = System.currentTimeMillis() - t0;

        // ---- 第二段：乱序施加各种动作 ----
        List<MallOrder> orders = ordersOf(volunteers);
        List<Runnable> chaos = new ArrayList<>();
        Random rnd = new Random(917L);
        for (MallOrder o : orders) {
            int roll = rnd.nextInt(10);
            if (o.getStatus() == MallOrderStatus.AWAITING_PAYMENT) {
                TradeVOs.Prepay prepay = orderService.pay(o.getId(), o.getVolunteerId(), "c" + o.getId());
                if (roll < 4) {
                    chaos.add(() -> track("付款回调", () -> callback(prepay)));
                    chaos.add(() -> track("付款回调·重投", () -> callback(prepay)));
                } else if (roll < 6) {
                    chaos.add(() -> track("取消", () -> orderService.cancel(o.getId(), o.getVolunteerId())));
                    chaos.add(() -> track("付款回调", () -> callback(prepay)));
                } else if (roll < 8) {
                    expire(o.getId(), prepay.getOutTradeNo());
                    chaos.add(() -> track("超时同步", orderService::syncAwaitingPayments));
                    chaos.add(() -> track("付款回调", () -> callback(prepay)));
                } else {
                    chaos.add(() -> track("付款回调", () -> callback(prepay)));
                    chaos.add(() -> track("驳回", () -> orderService.reject(o.getId(), "压测驳回", ADMIN)));
                }
            } else {
                if (roll < 5) {
                    chaos.add(() -> track("审核通过", () -> orderService.approve(o.getId(), ADMIN)));
                } else if (roll < 8) {
                    chaos.add(() -> track("驳回", () -> orderService.reject(o.getId(), "压测驳回", ADMIN)));
                } else {
                    chaos.add(() -> track("取消", () -> orderService.cancel(o.getId(), o.getVolunteerId())));
                }
            }
        }
        Collections.shuffle(chaos, rnd);
        t0 = System.currentTimeMillis();
        runConcurrently(chaos);
        long chaosWall = System.currentTimeMillis() - t0;

        // 事后补一轮：付了款又被驳回、还没退的，再驳一次（已驳回的会被拒，无妨）；剩下的待支付全部过期再同步
        for (MallOrder o : ordersOf(volunteers)) {
            if (o.getStatus() == MallOrderStatus.AWAITING_PAYMENT) {
                expire(o.getId(), null);
            }
        }
        orderService.syncAwaitingPayments();

        System.out.println("\n==== 商城现金/快递压测：下单 " + placements.size() + " 次 " + placeWall + " ms；乱序动作 "
                + chaos.size() + " 次 / " + THREADS + " 线程 / " + chaosWall + " ms ====\n  " + new TreeMap<>(outcomes));

        assertTrue(failures.isEmpty(), "除业务拒绝外不应有任何异常（死锁也算）：" + failures);
        List<MallOrder> finals = ordersOf(volunteers);
        Map<Long, Integer> heldBySpec = new ConcurrentHashMap<>();
        Map<Long, Integer> spentByVolunteer = new ConcurrentHashMap<>();
        for (MallOrder o : finals) {
            assertTrue(o.getStatus() != MallOrderStatus.AWAITING_PAYMENT, "不应有单子停在待支付：" + o.getId());
            boolean released = MallOrderStatus.isRefunded(o.getStatus());
            Integer trade = tradeStatusOf(o.getId());
            if (o.getPayCashFen() > 0) {
                if (o.getStatus() == MallOrderStatus.CANCELLED || (released && o.getTradeOrderId() == null)) {
                    assertTrue(trade == null || trade != TradeFlow.ORDER_PAID,
                            "已取消 / 未付款驳回的单，交易单不能是已支付：" + o.getId() + " trade=" + trade);
                } else if (released) {
                    assertEquals(TradeFlow.ORDER_REFUNDED, trade, "付过款又被驳回的，钱要原路退回：" + o.getId());
                } else {
                    assertEquals(TradeFlow.ORDER_PAID, trade, "在走流程的现金单必须已付：" + o.getId());
                }
            }
            if (!released) {
                heldBySpec.merge(o.getSpecId(), 1, Integer::sum);
                spentByVolunteer.merge(o.getVolunteerId(), o.getPoints(), Integer::sum);
            }
        }
        for (long spec : specs) {
            assertEquals(INITIAL_STOCK - heldBySpec.getOrDefault(spec, 0), stock(spec), "库存守恒：规格 " + spec);
        }
        for (Long v : volunteers) {
            assertEquals(INITIAL_POINTS - spentByVolunteer.getOrDefault(v, 0), pointService.balanceOf(v),
                    "积分守恒：志愿者 " + v);
        }
    }

    // ---------------- helpers ----------------

    private void callback(TradeVOs.Prepay prepay) {
        tradeOrderService.applyPaidResult(prepay.getOutTradeNo(), "wx-" + prepay.getOutTradeNo(),
                prepay.getAmountFen(), LocalDateTime.now().withNano(0), "openid", "{}", TradeFlow.SOURCE_CALLBACK);
    }

    private void expire(Long orderId, String outTradeNo) {
        jdbc.update("UPDATE mall_order SET pay_expire_time = ? WHERE id = ?", LocalDateTime.now().minusMinutes(1), orderId);
        jdbc.update("UPDATE trade_order SET expire_time = ? WHERE biz_type = ? AND biz_no = ? AND status = ?",
                LocalDateTime.now().minusMinutes(1), TradeFlow.BIZ_MALL_SHIPPING, String.valueOf(orderId),
                TradeFlow.ORDER_PENDING);
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

    private List<MallOrder> ordersOf(List<Long> volunteers) {
        String in = String.join(",", volunteers.stream().map(String::valueOf).toList());
        return jdbc.query("SELECT id, volunteer_id, spec_id, status, points, pay_cash_fen, trade_order_id FROM mall_order "
                + "WHERE volunteer_id IN (" + in + ") ORDER BY id", (rs, i) -> {
                    MallOrder o = new MallOrder();
                    o.setId(rs.getLong("id"));
                    o.setVolunteerId(rs.getLong("volunteer_id"));
                    o.setSpecId(rs.getLong("spec_id"));
                    o.setStatus(rs.getInt("status"));
                    o.setPoints(rs.getInt("points"));
                    o.setPayCashFen(rs.getInt("pay_cash_fen"));
                    long t = rs.getLong("trade_order_id");
                    o.setTradeOrderId(rs.wasNull() ? null : t);
                    return o;
                });
    }

    private Integer tradeStatusOf(Long orderId) {
        List<Integer> s = jdbc.queryForList("SELECT status FROM trade_order WHERE biz_type = ? AND biz_no = ? "
                + "ORDER BY id DESC LIMIT 1", Integer.class, TradeFlow.BIZ_MALL_SHIPPING, String.valueOf(orderId));
        return s.isEmpty() ? null : s.get(0);
    }

    private long spec(int cashFen) {
        long goodsId = SEQ.incrementAndGet();
        long specId = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO mall_goods (id, name, status, hidden, sort, create_time, update_time, is_deleted) "
                + "VALUES (?, '压测商品', ?, 0, 0, NOW(), NOW(), 0)", goodsId, MallGoodsStatus.ON_SALE);
        jdbc.update("INSERT INTO mall_goods_spec (id, goods_id, name, points, cash_fen, stock, sort, create_time, "
                + "update_time, is_deleted) VALUES (?, ?, '默认', 20, ?, ?, 0, NOW(), NOW(), 0)",
                specId, goodsId, cashFen, INITIAL_STOCK);
        return specId;
    }

    private void givePoints(long volunteerId, int amount) {
        jdbc.update("INSERT INTO point_record (volunteer_id, change_amount, source_type, source_id, remark, "
                + "operator_type, create_time, update_time, is_deleted) VALUES (?, ?, ?, NULL, '用例预置', 0, NOW(), NOW(), 0)",
                volunteerId, amount, PointSourceType.MANUAL);
    }

    private int stock(long specId) {
        return jdbc.queryForObject("SELECT stock FROM mall_goods_spec WHERE id = ?", Integer.class, specId);
    }
}
