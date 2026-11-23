package com.hengde.donate.service;

import com.hengde.donate.constant.DonationFlow;
import com.hengde.trade.event.TradePaidEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 捐款付款成功 → 捐款记录「待支付 → 已到账」并记进已筹金额（V3 捐款批）。
 *
 * <p>与 {@code MallPaymentListener} 同形：<b>不是唯一通路</b>，事件只发一次、不持久，
 * {@code DonationSyncJob} 定期拿待支付的捐款去问 trade；这里失败只记日志，两边都幂等。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class DonationPaymentListener {

    private DonationService donationService;

    @Autowired
    public void setDonationService(DonationService donationService) {
        this.donationService = donationService;
    }

    /**
     * {@code fallbackExecution = true} 是必需的：trade 在事务之外发布这个事件（否则每个付款回调要同时占两条连接，
     * 并发一高整个连接池卡死——见 {@code TradePaidEvent}），没有它，这个监听器<b>一次都不会被调用</b>，
     * 所有付款都只能等补偿任务慢慢推进。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTradePaid(TradePaidEvent event) {
        if (!DonationFlow.isValidBiz(event.bizType())) {
            return;
        }
        try {
            donationService.onPaid(event.bizType(), event.bizNo(), event.tradeOrderId());
        } catch (RuntimeException e) {
            log.error("[DONATION] 付款成功回写失败，等补偿任务 bizType={} bizNo={} tradeOrderId={}",
                    event.bizType(), event.bizNo(), event.tradeOrderId(), e);
        }
    }
}
