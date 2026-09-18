package com.hengde.trade.service;

import com.hengde.common.exception.BusinessException;
import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.gateway.PaymentGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 支付 / 退款回调的处理（V3 trade 批）。
 *
 * <p><b>验签与解密必须在这里做，不能在 api 的控制器里做</b>（V3规划 D1 的规矩 2）：
 * 控制器只负责「读原始报文 + 转发」，否则支付逻辑会一点点长到 api 里去。</p>
 *
 * <p><b>回调不是唯一通路，只是最快的那条</b>：它会丢、会重复、会乱序。所以这里什么都不自己做，
 * 一律汇到 {@link TradeOrderService#applyPaidResult} 与 {@link TradeRefundService#applyRefundResult}——
 * 主动查单与扫描任务走的也是那两个方法（D2）。</p>
 *
 * <p><b>什么时候回 success</b>：只要「这条通知我们已经处理完（含之前就处理过）」就回 success，
 * 让渠道别再重投；<b>验签失败、金额不符、单号不认识一律回 fail 并记 ERROR</b>——
 * 那几种情况要么是伪造，要么是我们这边真的出了问题，<b>回 success 等于把它藏起来</b>。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class TradeCallbackService {

    private PaymentGateway gateway;
    private TradeOrderService orderService;
    private TradeRefundService refundService;

    @Autowired
    public void setGateway(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    @Autowired
    public void setOrderService(TradeOrderService orderService) {
        this.orderService = orderService;
    }

    @Autowired
    public void setRefundService(TradeRefundService refundService) {
        this.refundService = refundService;
    }

    /** 支付结果通知。 */
    public Ack handlePay(PaymentGateway.CallbackHeaders headers, String body) {
        PaymentGateway.CallbackResult r;
        try {
            r = gateway.verifyAndDecrypt(headers, body);
        } catch (RuntimeException e) {
            // 验签 / 解密失败：这是安全事件，不是业务异常
            log.error("[TRADE] 支付回调验签或解密失败，已拒绝", e);
            return Ack.fail("验签失败");
        }
        if (!PaymentGateway.CallbackResult.KIND_PAY.equals(r.kind())) {
            log.warn("[TRADE] 支付回调里收到了非支付通知 kind={}", r.kind());
            return Ack.fail("报文类型不对");
        }
        if (!r.success()) {
            // 支付失败的通知：单子留在待支付，等用户重付或超时关单
            log.info("[TRADE] 收到支付失败通知 outTradeNo={}", r.outTradeNo());
            return Ack.ok();
        }
        try {
            orderService.applyPaidResult(r.outTradeNo(), r.transactionId(),
                    r.amountFen() == null ? -1 : r.amountFen(), r.successTime(), r.payerOpenid(),
                    r.rawJson(), TradeFlow.SOURCE_CALLBACK);
            return Ack.ok();
        } catch (BusinessException e) {
            // 金额不符 / 单号不认识：**不回 success**——渠道会重投，而我们要的正是「别把它当处理完了」
            log.error("[TRADE] 支付回调处理失败 outTradeNo={} transactionId={} 原因={}",
                    r.outTradeNo(), r.transactionId(), e.getMessage());
            return Ack.fail(e.getMessage());
        }
    }

    /** 退款结果通知。 */
    public Ack handleRefund(PaymentGateway.CallbackHeaders headers, String body) {
        PaymentGateway.CallbackResult r;
        try {
            r = gateway.verifyAndDecrypt(headers, body);
        } catch (RuntimeException e) {
            log.error("[TRADE] 退款回调验签或解密失败，已拒绝", e);
            return Ack.fail("验签失败");
        }
        if (!PaymentGateway.CallbackResult.KIND_REFUND.equals(r.kind())) {
            log.warn("[TRADE] 退款回调里收到了非退款通知 kind={}", r.kind());
            return Ack.fail("报文类型不对");
        }
        try {
            refundService.applyRefundResult(r.outRefundNo(), r.refundId(), r.success(),
                    r.successTime(), r.rawJson());
            return Ack.ok();
        } catch (BusinessException e) {
            log.error("[TRADE] 退款回调处理失败 outRefundNo={} 原因={}", r.outRefundNo(), e.getMessage());
            return Ack.fail(e.getMessage());
        }
    }

    /**
     * 给渠道的应答。
     *
     * <p>控制器据此拼微信要的 {@code {"code":"SUCCESS"}} / {@code {"code":"FAIL","message":"..."}}，
     * 并设对应的 HTTP 状态码——<b>拼报文是控制器的事，判成败是本服务的事</b>。</p>
     */
    public record Ack(boolean success, String message) {

        public static Ack ok() {
            return new Ack(true, "OK");
        }

        public static Ack fail(String message) {
            return new Ack(false, message);
        }
    }
}
