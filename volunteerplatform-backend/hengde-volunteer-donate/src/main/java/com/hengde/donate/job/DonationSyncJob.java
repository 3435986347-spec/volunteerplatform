package com.hengde.donate.job;

import com.hengde.donate.service.DonationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 待支付捐款同步（V3 捐款批）——触发层：付款成功事件丢了的照样到账，过了付款截止的取消。
 * 这是「钱收了、账上没有」的补偿，<b>不是可选项</b>。
 *
 * @author hengde
 */
@Slf4j
@Component
public class DonationSyncJob {

    private DonationService donationService;

    @Autowired
    public void setDonationService(DonationService donationService) {
        this.donationService = donationService;
    }

    @Scheduled(cron = "${hengde.donate.donation.sync-cron:45 */2 * * * ?}")
    public void sync() {
        try {
            int n = donationService.syncAwaitingDonations();
            if (n > 0) {
                log.info("[DONATION] 待支付同步：本轮到账或取消 {} 笔", n);
            }
        } catch (Exception e) {
            log.error("[DONATION] 待支付同步任务执行失败", e);
        }
    }
}
