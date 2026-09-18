package com.hengde.trade;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.trade.config.TradeProperties;
import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.dto.TradeDTOs;
import com.hengde.trade.gateway.PaymentGateway;
import com.hengde.trade.job.TradeScanJob;
import com.hengde.trade.service.TradeCallbackService;
import com.hengde.trade.service.TradeOrderService;
import com.hengde.trade.support.FakePaymentGateway;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回调与分钟级扫描（V3 trade 批）——<b>支付回写四道里的第一、二道</b>（V3规划 D2；第四道对账见 {@code TradeReconcileTest}）。
 *
 * <p>D2 明确要求回调用例覆盖<b>重复投递 / 乱序投递 / 金额不符</b>，这里逐条钉住；
 * 外加「验签失败必须回 FAIL」——<b>回 SUCCESS 等于把一次可疑投递藏起来，而渠道的重投正是我们要的第二次机会</b>。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class TradeCallbackAndScanTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);
    private static final PaymentGateway.CallbackHeaders HEADERS =
            new PaymentGateway.CallbackHeaders("serial", "signature", "1700000000", "nonce");

    @Autowired
    private TradeOrderService orderService;
    @Autowired
    private TradeCallbackService callbackService;
    @Autowired
    private TradeScanJob scanJob;
    @Autowired
    private TradeProperties properties;
    @Autowired
    private FakePaymentGateway gateway;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    @Test
    void duplicateDelivery_isAckedOnce_andLeavesOneRow() {
        String out = newOrder(700);
        String txn = "wx-" + SEQ.incrementAndGet();
        String body = payBody(out, txn, 700);

        assertTrue(callbackService.handlePay(HEADERS, body).success());
        assertTrue(callbackService.handlePay(HEADERS, body).success(),
                "重复投递也要回 SUCCESS——否则渠道会一直重投一条已经处理完的通知");
        assertEquals(1, paymentCount(out), "同一个渠道单号只该有一条流水");
        assertEquals(TradeFlow.ORDER_PAID, statusOf(out));
    }

    @Test
    void outOfOrderDelivery_scanFirstThenCallback_staysConsistent() {
        String out = newOrder(450);
        String txn = "wx-" + SEQ.incrementAndGet();
        // 扫描任务先把它查成已支付（回调还堵在路上）
        gateway.markPaidOnRemote(out, txn, 450);
        assertTrue(orderService.queryAndSync(out, TradeFlow.SOURCE_SCAN));

        // 迟到的回调：照样回 SUCCESS，但不能再写一条流水、也不能把状态再动一次
        assertTrue(callbackService.handlePay(HEADERS, payBody(out, txn, 450)).success());
        assertEquals(1, paymentCount(out));
        assertEquals(TradeFlow.ORDER_PAID, statusOf(out));
        assertEquals(TradeFlow.SOURCE_SCAN, jdbc.queryForObject(
                "SELECT source FROM trade_payment p JOIN trade_order o ON o.id = p.trade_order_id "
                        + "WHERE o.out_trade_no = ?", Integer.class, out),
                "先到的那条才是写流水的那条");
    }

    @Test
    void amountMismatch_isNotAcked() {
        String out = newOrder(1000);

        TradeCallbackService.Ack ack = callbackService.handlePay(HEADERS,
                payBody(out, "wx-" + SEQ.incrementAndGet(), 1));

        assertFalse(ack.success(), "金额不符必须回 FAIL：它要么是伪造，要么是我们这边错了，藏起来最糟");
        assertEquals(TradeFlow.ORDER_PENDING, statusOf(out));
        assertEquals(0, paymentCount(out));
    }

    @Test
    void badSignature_isRejected() {
        String out = newOrder(200);
        gateway.setVerificationFails(true);

        TradeCallbackService.Ack ack = callbackService.handlePay(HEADERS,
                payBody(out, "wx-" + SEQ.incrementAndGet(), 200));

        assertFalse(ack.success());
        assertEquals(TradeFlow.ORDER_PENDING, statusOf(out));
        assertEquals(0, paymentCount(out));
    }

    @Test
    void unknownOrder_isNotAcked() {
        TradeCallbackService.Ack ack = callbackService.handlePay(HEADERS,
                payBody("HD-not-mine-" + SEQ.incrementAndGet(), "wx-x", 100));
        assertFalse(ack.success(), "不认识的单号不能当成处理完了");
    }

    @Test
    void scanJob_picksUpOrdersWhoseCallbackWasLost() {
        String out = newOrder(310);
        String txn = "wx-" + SEQ.incrementAndGet();
        gateway.markPaidOnRemote(out, txn, 310);
        // 扫描只捞「下单已超过冷却期」的单：把创建时间往前挪
        jdbc.update("UPDATE trade_order SET create_time = ? WHERE out_trade_no = ?",
                LocalDateTime.now().minusHours(1), out);

        scanJob.scan();

        assertEquals(TradeFlow.ORDER_PAID, statusOf(out), "回调丢了也要由扫描把它推到终态");
        assertEquals(TradeFlow.SOURCE_SCAN, jdbc.queryForObject(
                "SELECT source FROM trade_payment p JOIN trade_order o ON o.id = p.trade_order_id "
                        + "WHERE o.out_trade_no = ?", Integer.class, out));
    }

    @Test
    void scanJob_respectsTheSwitch() {
        String out = newOrder(320);
        gateway.markPaidOnRemote(out, "wx-" + SEQ.incrementAndGet(), 320);
        jdbc.update("UPDATE trade_order SET create_time = ? WHERE out_trade_no = ?",
                LocalDateTime.now().minusHours(1), out);

        boolean original = properties.getScan().isEnabled();
        try {
            properties.getScan().setEnabled(false);
            scanJob.scan();
            assertEquals(TradeFlow.ORDER_PENDING, statusOf(out), "开关关掉就什么都不做");

            properties.getScan().setEnabled(true);
            scanJob.scan();
            assertEquals(TradeFlow.ORDER_PAID, statusOf(out), "打开后立刻推进——证明上面的不动确实归因于开关");
        } finally {
            properties.getScan().setEnabled(original);
        }
    }

    // ---------- helpers ----------

    private String newOrder(int amountFen) {
        TradeDTOs.CreateOrder d = new TradeDTOs.CreateOrder();
        d.setBizType(TradeFlow.BIZ_CROWDFUND);
        d.setBizNo("cb-biz-" + SEQ.incrementAndGet());
        d.setVolunteerId(3003L);
        d.setPayerOpenid("openid-cb");
        d.setSubject("用例捐款");
        d.setAmountFen(amountFen);
        return orderService.createOrder(d).getOutTradeNo();
    }

    /** 假渠道的「报文」：一层扁平 JSON，由用例自己拼（真实现那一段是 APIv3 验签 + 解密）。 */
    private static String payBody(String outTradeNo, String transactionId, int amountFen) {
        return "{\"kind\":\"PAY\",\"outTradeNo\":\"" + outTradeNo + "\",\"transactionId\":\"" + transactionId
                + "\",\"amountFen\":\"" + amountFen + "\",\"payerOpenid\":\"openid-cb\"}";
    }

    private int statusOf(String outTradeNo) {
        return jdbc.queryForObject("SELECT status FROM trade_order WHERE out_trade_no = ?",
                Integer.class, outTradeNo);
    }

    private int paymentCount(String outTradeNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trade_payment p JOIN trade_order o ON o.id = p.trade_order_id "
                + "WHERE o.out_trade_no = ?", Integer.class, outTradeNo);
    }
}
