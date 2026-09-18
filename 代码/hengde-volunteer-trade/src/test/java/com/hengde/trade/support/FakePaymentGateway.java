package com.hengde.trade.support;

import com.hengde.trade.gateway.PaymentGateway;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 假的支付渠道：让<b>我们自己的逻辑</b>（状态机、幂等、金额比对、扫描补偿、对账）在没有商户资质时也能被完整测试。
 *
 * <p>形状仿 donate 测试里替换 {@code LogisticsClient} 的做法。{@code @Primary} 让它在测试上下文里
 * 顶掉真实现——真实现要商户号与证书，在测试里根本起不来。</p>
 *
 * <p>⚠️ <b>它证明不了「支付通了」</b>：回调段的验签与解密是真实现的事，
 * 没有商户资质与备案域名就无法端到端验证（V3规划·trade 批的可验证性边界）。</p>
 *
 * @author hengde
 */
@Primary
@Component
public class FakePaymentGateway implements PaymentGateway {

    /** 渠道侧的「账本」：outTradeNo → 这笔在渠道那边是什么状态。 */
    private final Map<String, QueryResult> remote = new ConcurrentHashMap<>();
    private final List<String> closedOrders = new ArrayList<>();
    private final List<String> refunds = new ArrayList<>();
    private final AtomicInteger queryCount = new AtomicInteger();

    private volatile boolean enabled = true;
    private volatile boolean verificationFails = false;
    private volatile boolean queryFails = false;
    private volatile boolean refundAccepted = true;

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
        remote.putIfAbsent(outTradeNo, new QueryResult(true, false, null, amountFen, null, "{\"stub\":\"unpaid\"}"));
        return new PrepayResult("prepay_" + outTradeNo, "sign", "nonce", "1700000000", "RSA");
    }

    @Override
    public QueryResult query(String outTradeNo) {
        queryCount.incrementAndGet();
        if (queryFails) {
            throw new PaymentException("渠道查询失败（用例故意制造）");
        }
        QueryResult r = remote.get(outTradeNo);
        return r == null ? QueryResult.notFound() : r;
    }

    @Override
    public void close(String outTradeNo) {
        closedOrders.add(outTradeNo);
        remote.remove(outTradeNo);
    }

    @Override
    public RefundResult refund(String outTradeNo, String outRefundNo, int refundFen, int totalFen, String reason) {
        if (!refundAccepted) {
            throw new PaymentException("渠道拒绝退款（用例故意制造）");
        }
        refunds.add(outRefundNo);
        return new RefundResult("wxrefund_" + outRefundNo, true, "{\"stub\":\"refund\"}");
    }

    /**
     * 「验签并解密」：用例直接把要投递的内容当报文传进来（JSON 由用例拼），这里只做格式解析。
     *
     * <p>真实现里这一步是 APIv3 验签 + AES-GCM 解密；这里用 {@link #verificationFails} 模拟验签失败，
     * 好让「验签失败必须回 FAIL」这条路径也被测到。</p>
     */
    @Override
    public CallbackResult verifyAndDecrypt(CallbackHeaders headers, String body) {
        if (verificationFails) {
            throw new PaymentException("验签失败（用例故意制造）");
        }
        if (body == null || body.isBlank()) {
            throw new PaymentException("报文为空");
        }
        Map<String, String> m = parse(body);
        String kind = m.getOrDefault("kind", CallbackResult.KIND_PAY);
        Integer amount = m.get("amountFen") == null ? null : Integer.valueOf(m.get("amountFen"));
        LocalDateTime successTime = m.get("successTime") == null ? LocalDateTime.now()
                : LocalDateTime.parse(m.get("successTime"));
        return new CallbackResult(kind, m.get("outTradeNo"), m.get("transactionId"), m.get("outRefundNo"),
                m.get("refundId"), !"false".equals(m.get("success")), amount, successTime,
                m.get("payerOpenid"), body);
    }

    // ---------- 用例操控渠道侧状态的入口 ----------

    /** 让渠道那边「收到了钱」。 */
    public void markPaidOnRemote(String outTradeNo, String transactionId, int amountFen) {
        remote.put(outTradeNo, new QueryResult(true, true, transactionId, amountFen,
                LocalDateTime.now().withNano(0), "{\"stub\":\"paid\"}"));
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setVerificationFails(boolean verificationFails) {
        this.verificationFails = verificationFails;
    }

    public void setQueryFails(boolean queryFails) {
        this.queryFails = queryFails;
    }

    public void setRefundAccepted(boolean refundAccepted) {
        this.refundAccepted = refundAccepted;
    }

    public List<String> closedOrders() {
        return closedOrders;
    }

    public List<String> refunds() {
        return refunds;
    }

    public int queryCount() {
        return queryCount.get();
    }

    public void reset() {
        remote.clear();
        closedOrders.clear();
        refunds.clear();
        queryCount.set(0);
        enabled = true;
        verificationFails = false;
        queryFails = false;
        refundAccepted = true;
    }

    /** 极简 JSON 解析：用例拼的报文只有一层扁平的字符串字段，不引第三方库。 */
    private static Map<String, String> parse(String json) {
        Map<String, String> out = new ConcurrentHashMap<>();
        String body = json.trim();
        if (body.startsWith("{")) {
            body = body.substring(1);
        }
        if (body.endsWith("}")) {
            body = body.substring(0, body.length() - 1);
        }
        for (String part : body.split(",")) {
            String[] kv = part.split(":", 2);
            if (kv.length == 2) {
                out.put(unquote(kv[0]), unquote(kv[1]));
            }
        }
        return out;
    }

    private static String unquote(String s) {
        String v = s.trim();
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }
}
