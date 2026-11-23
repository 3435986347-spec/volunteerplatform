package com.hengde.trade.gateway;

import java.time.LocalDateTime;

/**
 * 支付渠道的端口。生产实现是微信支付；测试里用一个假的替换它，
 * 这样<b>我们自己的逻辑</b>（状态机、幂等、金额比对、扫描补偿、对账）可以在没有商户资质时被完整测试。
 *
 * <p>形状仿 donate 的 {@code LogisticsClient}：先有端口与假实现，真实现随资质到位再接。</p>
 *
 * <p>⚠️ <b>但别把「用例全绿」当成「支付通了」</b>（V3规划·trade 批的可验证性边界）：
 * 没有商户资质与备案域名，回调段<b>无法端到端验证</b>，用例只能到
 * 「本地状态机 + 模拟回调报文 + 验签」为止。</p>
 *
 * @author hengde
 */
public interface PaymentGateway {

    /** 是否开通（有商户资质且配置齐全）。未开通时下单一律被拒绝，<b>不伪造成功</b>。 */
    boolean enabled();

    /**
     * 下单（JSAPI / 小程序支付），返回给前端唤起支付所需的参数。
     *
     * @param outTradeNo 我方单号
     * @param amountFen  金额（分）
     * @param subject    商品描述
     * @param payerOpenid 付款人 openid
     * @param expireAt   过期时刻
     * @return 唤起支付的参数（prepayId + 签名等）
     * @throws PaymentException 渠道拒绝或网络失败
     */
    PrepayResult prepay(String outTradeNo, int amountFen, String subject, String payerOpenid,
                        LocalDateTime expireAt);

    /**
     * 主动查单。<b>本地与渠道不一致时以它为准</b>（V3规划·trade 批）。
     *
     * @param outTradeNo 我方单号
     * @return 渠道侧的当前状态；查不到返回 {@link QueryResult#notFound()}
     */
    QueryResult query(String outTradeNo);

    /**
     * 关单。已支付的单不能关——调用方须先判状态。
     *
     * @param outTradeNo 我方单号
     */
    void close(String outTradeNo);

    /**
     * 申请退款。
     *
     * @param outTradeNo  原交易单号
     * @param outRefundNo 我方退款单号
     * @param refundFen   退款金额（分）
     * @param totalFen    原单总额（分）
     * @param reason      退款原因
     * @return 渠道受理结果（退款是异步的，成功与否以回调 / 查询为准）
     */
    RefundResult refund(String outTradeNo, String outRefundNo, int refundFen, int totalFen, String reason);

    /**
     * 验签并解密一条回调报文。<b>必须在本模块内做</b>（D1 的规矩 2）：
     * api 的控制器只负责「读原始报文 + 转发」。
     *
     * @param headers 回调请求头（签名、序列号、时间戳、随机串）
     * @param body    原始报文
     * @return 解密后的通知内容
     * @throws PaymentException 验签失败、解密失败、报文不认识——<b>一律拒绝，不要「尽力而为地解析」</b>
     */
    CallbackResult verifyAndDecrypt(CallbackHeaders headers, String body);

    /** 下单结果。 */
    record PrepayResult(String prepayId, String paySign, String nonceStr, String timeStamp, String signType) {
    }

    /**
     * 查单结果。
     *
     * @param found         渠道上是否有这张单
     * @param paid          是否已支付
     * @param transactionId 渠道支付单号
     * @param amountFen     渠道侧金额（分）——<b>调用方必须与本地单比对</b>
     * @param successTime   支付完成时刻
     * @param rawJson       原始报文，落库留底
     */
    record QueryResult(boolean found, boolean paid, String transactionId, Integer amountFen,
                       LocalDateTime successTime, String rawJson) {

        public static QueryResult notFound() {
            return new QueryResult(false, false, null, null, null, null);
        }
    }

    /** 退款受理结果。 */
    record RefundResult(String refundId, boolean success, String rawJson) {
    }

    /** 回调请求头（APIv3 验签用）。 */
    record CallbackHeaders(String serial, String signature, String timestamp, String nonce) {
    }

    /**
     * 回调解密结果。
     *
     * @param kind          PAY 支付通知 / REFUND 退款通知
     * @param outTradeNo    我方单号
     * @param transactionId 渠道支付单号
     * @param outRefundNo   我方退款单号（退款通知才有）
     * @param refundId      渠道退款单号（退款通知才有）
     * @param success       该通知表示成功与否
     * @param amountFen     金额（分）——<b>必须与本地单比对，不符一律拒绝并告警</b>
     * @param successTime   完成时刻
     * @param payerOpenid   付款人
     * @param rawJson       解密后的报文，落库留底
     */
    record CallbackResult(String kind, String outTradeNo, String transactionId, String outRefundNo,
                          String refundId, boolean success, Integer amountFen, LocalDateTime successTime,
                          String payerOpenid, String rawJson) {

        public static final String KIND_PAY = "PAY";
        public static final String KIND_REFUND = "REFUND";
    }

    /** 渠道侧失败（与「查无此单」区分开：那是正常结果，不是异常）。 */
    class PaymentException extends RuntimeException {

        public PaymentException(String message) {
            super(message);
        }

        public PaymentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
