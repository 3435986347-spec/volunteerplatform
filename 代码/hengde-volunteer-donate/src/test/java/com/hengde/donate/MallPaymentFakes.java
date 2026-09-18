package com.hengde.donate;

import com.hengde.auth.service.MiniappIdentityService;
import com.hengde.common.exception.BusinessException;
import com.hengde.trade.gateway.PaymentGateway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 商城快递批用例的两个替身：假微信支付渠道、假小程序身份。
 *
 * <p>没有商户号与小程序 appid 时真实现起不来；<b>我们自己的逻辑</b>——待支付占位、付款回写、超时取消、
 * 取消与付款赛跑、驳回后原路退款——必须照样被完整测到。⚠️ 全绿不等于「支付通了」，边界同 trade 批。</p>
 *
 * @author hengde
 */
@TestConfiguration
class MallPaymentFakes {

    @Bean
    @Primary
    FakeGateway mallFakeGateway() {
        return new FakeGateway();
    }

    @Bean
    @Primary
    FakeIdentity mallFakeIdentity() {
        return new FakeIdentity();
    }

    /** 可编程的假渠道：渠道侧账本 + 开关。 */
    static class FakeGateway implements PaymentGateway {
        final Map<String, QueryResult> remote = new ConcurrentHashMap<>();
        final List<String> closed = new CopyOnWriteArrayList<>();
        final List<String> refunds = new CopyOnWriteArrayList<>();
        final AtomicInteger prepays = new AtomicInteger();
        volatile boolean enabled = true;
        volatile boolean refundFails = false;
        volatile String lastOpenid;

        void reset() {
            remote.clear();
            closed.clear();
            refunds.clear();
            prepays.set(0);
            enabled = true;
            refundFails = false;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public PrepayResult prepay(String outTradeNo, int amountFen, String subject, String payerOpenid,
                                   LocalDateTime expireAt) {
            if (!enabled) {
                throw new PaymentException("渠道未开通");
            }
            prepays.incrementAndGet();
            lastOpenid = payerOpenid;
            remote.putIfAbsent(outTradeNo, new QueryResult(true, false, null, amountFen, null, "{}"));
            return new PrepayResult("prepay_" + outTradeNo, "sign", "nonce", "1700000000", "RSA");
        }

        @Override
        public QueryResult query(String outTradeNo) {
            QueryResult r = remote.get(outTradeNo);
            return r == null ? QueryResult.notFound() : r;
        }

        @Override
        public void close(String outTradeNo) {
            closed.add(outTradeNo);
            remote.remove(outTradeNo);
        }

        @Override
        public RefundResult refund(String outTradeNo, String outRefundNo, int refundFen, int totalFen, String reason) {
            if (refundFails) {
                throw new PaymentException("渠道拒绝退款（用例故意制造）");
            }
            refunds.add(outRefundNo);
            return new RefundResult("wxrefund_" + outRefundNo, true, "{}");
        }

        @Override
        public CallbackResult verifyAndDecrypt(CallbackHeaders headers, String body) {
            throw new PaymentException("商城用例不走回调报文，直接调 applyPaidResult");
        }

        /** 让渠道那边「收到了钱」（回调丢了，只有查单才知道）。 */
        void markPaidOnRemote(String outTradeNo, String transactionId, int amountFen) {
            remote.put(outTradeNo, new QueryResult(true, true, transactionId, amountFen,
                    LocalDateTime.now().withNano(0), "{}"));
        }
    }

    /** 假小程序身份：code → "openid-" + code；code 为 bad 时模拟换取失败。 */
    static class FakeIdentity extends MiniappIdentityService {
        @Override
        public String openidOf(String code) {
            if (code == null || code.isBlank() || "bad".equals(code)) {
                throw new BusinessException("获取微信身份失败，请重试");
            }
            return "openid-" + code;
        }
    }
}
