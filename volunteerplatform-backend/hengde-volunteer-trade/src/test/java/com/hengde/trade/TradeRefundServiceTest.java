package com.hengde.trade;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.dto.TradeDTOs;
import com.hengde.trade.service.TradeOrderService;
import com.hengde.trade.service.TradeRefundService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退款（V3 trade 批）：额度、幂等、失败回退。
 *
 * <p><b>退款是异步的</b>：受理只代表「收到了」，成败以回调 / 查询回写为准。
 * 因此这里最关心两件事——<b>退不过头</b>（上限写在 UPDATE 的 WHERE 里），
 * 以及<b>失败时把占掉的额度退回去</b>（不退的话，那笔钱在账上永远算「已退」，之后再也退不出来）。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class TradeRefundServiceTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);
    private static final long ADMIN = 8801L;

    @Autowired
    private TradeOrderService orderService;
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
    void partialRefunds_addUpButNeverExceedTheOrder() {
        String outTradeNo = paidOrder(1000);

        refundService.refund(orderId(outTradeNo), refund(300, "少发了一件"), ADMIN);
        assertEquals(300, refundedOf(outTradeNo));
        assertEquals(TradeFlow.ORDER_PARTIAL_REFUNDED, statusOf(outTradeNo));

        refundService.refund(orderId(outTradeNo), refund(700, "剩下的也退"), ADMIN);
        assertEquals(1000, refundedOf(outTradeNo));
        assertEquals(TradeFlow.ORDER_REFUNDED, statusOf(outTradeNo), "退满即全额退款");

        BusinessException over = assertThrows(BusinessException.class,
                () -> refundService.refund(orderId(outTradeNo), refund(1, "再退一分"), ADMIN));
        assertTrue(over.getMessage().contains("只有已支付") || over.getMessage().contains("超过"), over.getMessage());
        assertEquals(1000, refundedOf(outTradeNo), "退过头必须挡住");
    }

    @Test
    void refundWithoutAmount_refundsTheWholeRemainder() {
        String outTradeNo = paidOrder(450);
        refundService.refund(orderId(outTradeNo), refund(null, "整单退"), ADMIN);
        assertEquals(450, refundedOf(outTradeNo));
        assertEquals(TradeFlow.ORDER_REFUNDED, statusOf(outTradeNo));
    }

    @Test
    void refundResult_isIdempotent_andFailureReleasesTheQuota() {
        String outTradeNo = paidOrder(600);
        String outRefundNo = refundService.refund(orderId(outTradeNo), refund(600, "整单退"), ADMIN);
        assertEquals(600, refundedOf(outTradeNo), "发起时就占住额度——渠道那边已经受理了，本地不能装作没退");

        refundService.applyRefundResult(outRefundNo, "wxr-1", true, LocalDateTime.now().withNano(0), "{}");
        assertEquals(TradeFlow.REFUND_SUCCESS, refundStatusOf(outRefundNo));

        // 重复投递：不再改动
        refundService.applyRefundResult(outRefundNo, "wxr-1", true, LocalDateTime.now(), "{}");
        assertEquals(TradeFlow.REFUND_SUCCESS, refundStatusOf(outRefundNo));
        assertEquals(600, refundedOf(outTradeNo));

        // 另一单：渠道回失败 → 额度退回去，单子回到「已支付」，还能再退一次
        String second = paidOrder(600);
        String failing = refundService.refund(orderId(second), refund(600, "先退"), ADMIN);
        refundService.applyRefundResult(failing, null, false, null, "{}");
        assertEquals(TradeFlow.REFUND_FAILED, refundStatusOf(failing));
        assertEquals(0, refundedOf(second), "退款失败必须把占掉的额度退回去");
        assertEquals(TradeFlow.ORDER_PAID, statusOf(second));
        assertEquals(600, jdbc.queryForObject("SELECT amount FROM trade_order WHERE out_trade_no = ?",
                Integer.class, second));
    }

    @Test
    void refundIsRefusedWhenGatewayIsOff_andQuotaIsNotLeftOccupied() {
        String outTradeNo = paidOrder(200);
        gateway.setEnabled(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> refundService.refund(orderId(outTradeNo), refund(200, "退"), ADMIN));
        assertTrue(e.getMessage().contains("尚未开通"), e.getMessage());
        assertEquals(0, refundedOf(outTradeNo), "没退成就不能把额度占着");
        assertEquals(TradeFlow.ORDER_PAID, statusOf(outTradeNo));
    }

    @Test
    void refundRejectedByChannel_marksFailedAndReleasesQuota() {
        String outTradeNo = paidOrder(350);
        gateway.setRefundAccepted(false);

        assertThrows(BusinessException.class,
                () -> refundService.refund(orderId(outTradeNo), refund(350, "退"), ADMIN));
        assertEquals(0, refundedOf(outTradeNo));
        assertEquals(TradeFlow.ORDER_PAID, statusOf(outTradeNo));
    }

    @Test
    void unpaidOrderCannotBeRefunded_andReasonIsRequired() {
        TradeVOs.Prepay p = orderService.createOrder(order(400));
        Long id = orderId(p.getOutTradeNo());

        assertTrue(assertThrows(BusinessException.class,
                () -> refundService.refund(id, refund(400, "退"), ADMIN))
                .getMessage().contains("只有已支付"));
        assertTrue(assertThrows(BusinessException.class,
                () -> refundService.refund(id, refund(400, "  "), ADMIN))
                .getMessage().contains("退款原因"));
        assertThrows(BusinessException.class, () -> refundService.refund(id, refund(400, "退"), null),
                "操作人必须给（D1 的规矩 1）");
    }

    // ---------- helpers ----------

    /** 造一张已支付的交易单，返回 out_trade_no。 */
    private String paidOrder(int amountFen) {
        TradeVOs.Prepay p = orderService.createOrder(order(amountFen));
        orderService.applyPaidResult(p.getOutTradeNo(), "wx-" + SEQ.incrementAndGet(), amountFen,
                LocalDateTime.now().withNano(0), "openid", "{}", TradeFlow.SOURCE_CALLBACK);
        return p.getOutTradeNo();
    }

    private static TradeDTOs.CreateOrder order(int amountFen) {
        TradeDTOs.CreateOrder d = new TradeDTOs.CreateOrder();
        d.setBizType(TradeFlow.BIZ_MALL_SHIPPING);
        d.setBizNo("refund-biz-" + SEQ.incrementAndGet());
        d.setVolunteerId(2002L);
        d.setPayerOpenid("openid-refund");
        d.setSubject("用例商品");
        d.setAmountFen(amountFen);
        return d;
    }

    private static TradeDTOs.Refund refund(Integer amountFen, String reason) {
        TradeDTOs.Refund d = new TradeDTOs.Refund();
        d.setAmountFen(amountFen);
        d.setReason(reason);
        return d;
    }

    private Long orderId(String outTradeNo) {
        return jdbc.queryForObject("SELECT id FROM trade_order WHERE out_trade_no = ?", Long.class, outTradeNo);
    }

    private int statusOf(String outTradeNo) {
        return jdbc.queryForObject("SELECT status FROM trade_order WHERE out_trade_no = ?",
                Integer.class, outTradeNo);
    }

    private int refundedOf(String outTradeNo) {
        return jdbc.queryForObject("SELECT refunded_amount FROM trade_order WHERE out_trade_no = ?",
                Integer.class, outTradeNo);
    }

    private int refundStatusOf(String outRefundNo) {
        return jdbc.queryForObject("SELECT status FROM trade_refund WHERE out_refund_no = ?",
                Integer.class, outRefundNo);
    }
}
