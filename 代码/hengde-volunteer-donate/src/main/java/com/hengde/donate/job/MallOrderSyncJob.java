package com.hengde.donate.job;

import com.hengde.donate.service.MallOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 商城兑换单的两个定时动作（商城快递批）——<b>只是触发层</b>，逻辑在 {@link MallOrderService}。
 *
 * <ul>
 *   <li><b>待支付同步</b>：付款成功事件丢了的照样推进，超过付款截止的取消并全部归还。
 *       这是「钱收了、单没发货」的补偿，<b>不是可选项</b>；</li>
 *   <li><b>自动确认收货</b>：发货满 N 天没点确认的，系统确认——否则那张单永远停在已发货、也评价不了。</li>
 * </ul>
 *
 * @author hengde
 */
@Slf4j
@Component
public class MallOrderSyncJob {

    private MallOrderService orderService;

    @Autowired
    public void setOrderService(MallOrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(cron = "${hengde.donate.mall.payment.sync-cron:30 */2 * * * ?}")
    public void syncPayments() {
        try {
            int n = orderService.syncAwaitingPayments();
            if (n > 0) {
                log.info("[MALL] 待支付同步：本轮推进或取消 {} 单", n);
            }
        } catch (Exception e) {
            log.error("[MALL] 待支付同步任务执行失败", e);
        }
    }

    @Scheduled(cron = "${hengde.donate.mall.express.auto-receive-cron:0 40 3 * * ?}")
    public void autoReceive() {
        try {
            int n = orderService.autoReceive();
            if (n > 0) {
                log.info("[MALL] 自动确认收货 {} 单", n);
            }
        } catch (Exception e) {
            log.error("[MALL] 自动确认收货任务执行失败", e);
        }
    }
}
