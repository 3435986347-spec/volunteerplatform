package com.hengde.donate.service;

import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.event.TradePaidEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 商城付款成功 → 兑换单从「待支付」推进到「待审核」（商城快递批）。
 *
 * <p><b>单独成一个组件</b>：理由同 honor 的 {@code CertificateAutoCreateListener}——监听方法与被调方法同类属自调用。</p>
 *
 * <p>⚠️ <b>它不是唯一通路</b>：{@code TradePaidEvent} 只发一次、进程内、不持久；
 * 事务提交后、这里跑完前进程挂掉，这张单就只能靠 {@code MallOrderSyncJob} 定期去问 trade。
 * 所以这里失败只记日志、不重试——重试是补偿任务的事，两边都幂等。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class MallPaymentListener {

    private MallOrderService orderService;

    @Autowired
    public void setOrderService(MallOrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * {@code fallbackExecution = true} 是必需的：trade 在事务之外发布这个事件（否则每个付款回调要同时占两条连接，
     * 并发一高整个连接池卡死——见 {@code TradePaidEvent}），没有它，这个监听器<b>一次都不会被调用</b>，
     * 所有付款都只能等补偿任务慢慢推进。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTradePaid(TradePaidEvent event) {
        if (event.bizType() == null || event.bizType() != TradeFlow.BIZ_MALL_SHIPPING) {
            return;
        }
        try {
            orderService.onPaid(event.bizNo(), event.tradeOrderId());
        } catch (RuntimeException e) {
            log.error("[MALL] 付款成功回写失败，等补偿任务 bizNo={} tradeOrderId={}", event.bizNo(),
                    event.tradeOrderId(), e);
        }
    }
}
