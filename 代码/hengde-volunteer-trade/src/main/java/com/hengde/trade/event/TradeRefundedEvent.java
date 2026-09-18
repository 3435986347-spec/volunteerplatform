package com.hengde.trade.event;

/**
 * 退款成功后发布的领域事件（业务侧据此撤销发货 / 冲正入账）。
 *
 * <p>与 {@link TradePaidEvent} 一样<b>在事务之外发布</b>（连接池那条理由），订阅方同样写 {@code fallbackExecution = true}。</p>
 *
 * <p>与 {@link TradePaidEvent} 同一条纪律：事件不持久，业务侧的处理必须幂等——
 * 退款结果也会由主动查询再驱动一次。</p>
 *
 * @param tradeOrderId 交易单 id
 * @param bizType      业务类型
 * @param bizNo        业务单据号
 * @param refundFen    本次退款金额（分）
 * @param outRefundNo  我方退款单号
 * @param fullyRefunded 是否已全额退完
 * @author hengde
 */
public record TradeRefundedEvent(Long tradeOrderId, Integer bizType, String bizNo, Integer refundFen,
                                 String outRefundNo, boolean fullyRefunded) {
}
