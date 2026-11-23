package com.hengde.trade.job;

import com.hengde.trade.config.TradeProperties;
import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.entity.TradeOrder;
import com.hengde.trade.service.TradeOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分钟级扫描：捞「已发起未终态」的交易单去主动查单——<b>支付回写四道里的第二道</b>（V3规划 D2）。
 *
 * <p><b>为什么不能只靠回调</b>：回调是进程外投递，会丢、会被防火墙挡、会在我们重启时打空。
 * <b>证书丢一次事件只是少一张证书，支付丢一次事件是钱收了、单没发货。</b>
 * 每日对账是第四道、不是唯一那道——一天之后才发现钱收了没发货，对付款人来说太慢了。</p>
 *
 * <p>本类只判开关、取数据、调服务、记日志，<b>不含业务逻辑</b>（同 honor 的 {@code RankingSnapshotJob}
 * 与 donate 的 {@code DonateTrackJob}）：日后换 XXL-Job 只是在旁边加一个处理器调同一个方法。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class TradeScanJob {

    private TradeProperties properties;
    private TradeOrderService orderService;

    @Autowired
    public void setProperties(TradeProperties properties) {
        this.properties = properties;
    }

    @Autowired
    public void setOrderService(TradeOrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(cron = "${hengde.trade.scan.cron:0 */2 * * * ?}")
    public void scan() {
        TradeProperties.Scan scan = properties.getScan();
        if (!scan.isEnabled()) {
            return;
        }
        List<TradeOrder> due = orderService.due(scan.getBatch());
        if (due.isEmpty()) {
            return;
        }
        int paid = 0;
        int failed = 0;
        for (TradeOrder order : due) {
            try {
                if (orderService.queryAndSync(order.getOutTradeNo(), TradeFlow.SOURCE_SCAN)) {
                    paid++;
                }
            } catch (RuntimeException e) {
                // 单条失败不影响这一轮里的其它单：下一轮还会捞到它
                failed++;
                log.error("[TRADE] 扫描查单失败 outTradeNo={}", order.getOutTradeNo(), e);
            }
        }
        log.info("[TRADE] 扫描查单：本轮 {} 单，其中 {} 单确认已支付，{} 单查询失败", due.size(), paid, failed);
    }
}
