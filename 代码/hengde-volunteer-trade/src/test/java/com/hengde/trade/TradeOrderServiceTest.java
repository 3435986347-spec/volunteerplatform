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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交易单的状态机与<b>支付回写</b>（V3 trade 批）。
 *
 * <p><b>这里测的是「我们自己的逻辑」</b>：幂等、金额比对、以渠道为准、过期关单。
 * 渠道用 {@link FakePaymentGateway} 顶替——没有商户资质，真实现根本起不来。
 * ⚠️ <b>别把这些用例全绿当成「支付通了」</b>：验签与解密那一段要真商户号与备案域名才验得了
 * （V3规划·trade 批的可验证性边界）。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL；trade 不用 Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class TradeOrderServiceTest {

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
    void createOrder_reusesTheLiveOrder_andRefusesWhenGatewayIsOff() {
        String bizNo = "biz-" + SEQ.incrementAndGet();

        gateway.setEnabled(false);
        BusinessException off = assertThrows(BusinessException.class, () -> orderService.createOrder(dto(bizNo, 100)));
        assertTrue(off.getMessage().contains("尚未开通"), off.getMessage());
        assertEquals(0, orderCount(bizNo), "渠道没开通就不该留下一张待支付的空单");

        gateway.setEnabled(true);
        TradeVOs.Prepay first = orderService.createOrder(dto(bizNo, 100));
        assertNotNull(first.getPrepayId());
        assertEquals("1.00", first.getAmountYuan(), "分 → 元的换算只在出参这一处做");

        TradeVOs.Prepay again = orderService.createOrder(dto(bizNo, 100));
        assertEquals(first.getOutTradeNo(), again.getOutTradeNo(), "同一条业务记录只会有一张活单");
        assertEquals(1, orderCount(bizNo));
    }

    @Test
    void applyPaidResult_isIdempotent_andRejectsAmountMismatch() {
        String bizNo = "biz-" + SEQ.incrementAndGet();
        TradeVOs.Prepay p = orderService.createOrder(dto(bizNo, 500));
        String txn = "wx-" + SEQ.incrementAndGet();

        assertTrue(orderService.applyPaidResult(p.getOutTradeNo(), txn, 500, LocalDateTime.now().withNano(0),
                "openid-1", "{}", TradeFlow.SOURCE_CALLBACK), "第一次投递应完成「待支付 → 已支付」");
        assertEquals(TradeFlow.ORDER_PAID, statusOf(p.getOutTradeNo()));
        assertEquals(1, paymentCount(p.getOutTradeNo()));

        // 重复投递（回调重投、查单与扫描同时到）：不再迁移，也不多记一条流水
        assertFalse(orderService.applyPaidResult(p.getOutTradeNo(), txn, 500, LocalDateTime.now(),
                "openid-1", "{}", TradeFlow.SOURCE_QUERY), "重复投递必须返回 false");
        assertEquals(1, paymentCount(p.getOutTradeNo()), "同一个渠道单号只该有一条流水（uk_transaction）");
    }

    @Test
    void amountMismatch_isRejectedAndChangesNothing() {
        String bizNo = "biz-" + SEQ.incrementAndGet();
        TradeVOs.Prepay p = orderService.createOrder(dto(bizNo, 800));

        BusinessException e = assertThrows(BusinessException.class,
                () -> orderService.applyPaidResult(p.getOutTradeNo(), "wx-bad", 1, LocalDateTime.now(),
                        "openid", "{}", TradeFlow.SOURCE_CALLBACK));
        assertTrue(e.getMessage().contains("金额"), e.getMessage());
        assertEquals(TradeFlow.ORDER_PENDING, statusOf(p.getOutTradeNo()), "金额不符不能改状态");
        assertEquals(0, paymentCount(p.getOutTradeNo()), "金额不符不能留下流水");
    }

    @Test
    void paymentForAClosedOrder_isRecordedNotDropped() {
        String bizNo = "biz-" + SEQ.incrementAndGet();
        TradeVOs.Prepay p = orderService.createOrder(dto(bizNo, 300));
        orderService.close(orderIdOf(p.getOutTradeNo()), 8801L);
        assertEquals(TradeFlow.ORDER_CLOSED, statusOf(p.getOutTradeNo()));

        boolean moved = orderService.applyPaidResult(p.getOutTradeNo(), "wx-late", 300,
                LocalDateTime.now().withNano(0), "openid", "{}", TradeFlow.SOURCE_CALLBACK);

        assertFalse(moved, "已关闭的单不该被改成已支付");
        assertEquals(TradeFlow.ORDER_CLOSED, statusOf(p.getOutTradeNo()));
        assertEquals(1, paymentCount(p.getOutTradeNo()),
                "钱是真收了：流水必须留下来等人工退款，静默丢弃等于把一笔已收的钱从系统里抹掉");
    }

    @Test
    void queryAndSync_takesTheChannelAsTruth() {
        String bizNo = "biz-" + SEQ.incrementAndGet();
        TradeVOs.Prepay p = orderService.createOrder(dto(bizNo, 660));

        // 渠道那边已经收到钱，而本地还是待支付（回调丢了）——主动查单要把它纠正过来
        gateway.markPaidOnRemote(p.getOutTradeNo(), "wx-" + SEQ.incrementAndGet(), 660);
        assertTrue(orderService.queryAndSync(p.getOutTradeNo(), TradeFlow.SOURCE_QUERY));
        assertEquals(TradeFlow.ORDER_PAID, statusOf(p.getOutTradeNo()));
        assertEquals(TradeFlow.SOURCE_QUERY, jdbc.queryForObject(
                "SELECT source FROM trade_payment WHERE trade_order_id = ?", Integer.class,
                orderIdOf(p.getOutTradeNo())), "流水要记清是哪条路写的");
    }

    @Test
    void expiredUnpaidOrder_isClosed() {
        String bizNo = "biz-" + SEQ.incrementAndGet();
        TradeVOs.Prepay p = orderService.createOrder(dto(bizNo, 120));
        jdbc.update("UPDATE trade_order SET expire_time = ? WHERE out_trade_no = ?",
                LocalDateTime.now().minusMinutes(1), p.getOutTradeNo());

        orderService.closeIfExpired(p.getOutTradeNo());
        assertEquals(TradeFlow.ORDER_CLOSED, statusOf(p.getOutTradeNo()));

        // 没过期的不许关
        TradeVOs.Prepay fresh = orderService.createOrder(dto("biz-" + SEQ.incrementAndGet(), 120));
        orderService.closeIfExpired(fresh.getOutTradeNo());
        assertEquals(TradeFlow.ORDER_PENDING, statusOf(fresh.getOutTradeNo()));
    }

    @Test
    void close_onlyWorksOnPendingOrders() {
        String bizNo = "biz-" + SEQ.incrementAndGet();
        TradeVOs.Prepay p = orderService.createOrder(dto(bizNo, 900));
        Long id = orderIdOf(p.getOutTradeNo());
        orderService.applyPaidResult(p.getOutTradeNo(), "wx-" + SEQ.incrementAndGet(), 900,
                LocalDateTime.now(), "openid", "{}", TradeFlow.SOURCE_CALLBACK);

        BusinessException e = assertThrows(BusinessException.class, () -> orderService.close(id, 8801L));
        assertTrue(e.getMessage().contains("只有待支付"), e.getMessage());
        assertThrows(BusinessException.class, () -> orderService.close(id, null), "操作人必须给（D1 的规矩 1）");
    }

    @Test
    void ttl_canBeOverriddenPerBizType() {
        // 众筹捐款不占资源，配置里给的是一天；商城快递费用默认 15 分钟
        TradeVOs.Prepay crowd = orderService.createOrder(
                dtoOf(TradeFlow.BIZ_CROWDFUND, "biz-" + SEQ.incrementAndGet(), 1000));
        TradeVOs.Prepay mall = orderService.createOrder(
                dtoOf(TradeFlow.BIZ_MALL_SHIPPING, "biz-" + SEQ.incrementAndGet(), 1000));
        assertTrue(crowd.getExpireTime().isAfter(mall.getExpireTime().plusHours(1)),
                "众筹的有效期应远长于商城：" + crowd.getExpireTime() + " vs " + mall.getExpireTime());
    }

    // ---------- helpers ----------

    private static TradeDTOs.CreateOrder dto(String bizNo, int amountFen) {
        return dtoOf(TradeFlow.BIZ_MALL_SHIPPING, bizNo, amountFen);
    }

    private static TradeDTOs.CreateOrder dtoOf(int bizType, String bizNo, int amountFen) {
        TradeDTOs.CreateOrder d = new TradeDTOs.CreateOrder();
        d.setBizType(bizType);
        d.setBizNo(bizNo);
        d.setVolunteerId(1001L);
        d.setPayerOpenid("openid-" + bizNo);
        d.setSubject("用例商品");
        d.setAmountFen(amountFen);
        return d;
    }

    private int orderCount(String bizNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE biz_no = ?", Integer.class, bizNo);
    }

    private int statusOf(String outTradeNo) {
        return jdbc.queryForObject("SELECT status FROM trade_order WHERE out_trade_no = ?",
                Integer.class, outTradeNo);
    }

    private Long orderIdOf(String outTradeNo) {
        return jdbc.queryForObject("SELECT id FROM trade_order WHERE out_trade_no = ?", Long.class, outTradeNo);
    }

    private int paymentCount(String outTradeNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trade_payment p JOIN trade_order o ON o.id = p.trade_order_id "
                + "WHERE o.out_trade_no = ?", Integer.class, outTradeNo);
    }

    @SuppressWarnings("unused")
    private TradeOrder unused() {
        return null;
    }
}
