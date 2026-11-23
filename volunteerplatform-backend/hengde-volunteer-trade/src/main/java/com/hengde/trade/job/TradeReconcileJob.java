package com.hengde.trade.job;

import com.hengde.trade.config.TradeProperties;
import com.hengde.trade.service.TradeReconcileService;
import com.hengde.trade.vo.TradeVOs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 每日对账：核对前一天——<b>支付回写四道里的第四道</b>（V3规划 D2）。
 *
 * <p>本类只判开关、调服务、记日志，<b>不含业务逻辑</b>（同 {@link TradeScanJob}）。
 * 结果由服务落库（{@code trade_reconcile_run}），后台 {@code GET /a/trade/reconciliations?onlyMismatch=true}
 * 看得到；<b>这里的日志只是给运维的附带信号，不是差异的唯一出口</b>。</p>
 *
 * <p>⚠️ 漏跑的那一天不会自动补：停机跨过凌晨那次执行，那一天就没有定时记录。
 * 列表里按日期看得出缺了哪天，用 {@code POST /a/trade/reconciliations} 手动补那一段。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class TradeReconcileJob {

    private TradeProperties properties;
    private TradeReconcileService reconcileService;

    @Autowired
    public void setProperties(TradeProperties properties) {
        this.properties = properties;
    }

    @Autowired
    public void setReconcileService(TradeReconcileService reconcileService) {
        this.reconcileService = reconcileService;
    }

    @Scheduled(cron = "${hengde.trade.reconcile.cron:0 30 3 * * ?}")
    public void reconcileYesterday() {
        if (!properties.getReconcile().isEnabled()) {
            return;
        }
        LocalDate day = LocalDate.now().minusDays(1);
        try {
            TradeVOs.Reconciliation r = reconcileService.reconcileDay(day);
            if (r.isSkipped()) {
                log.info("[TRADE] 每日对账 {}：渠道未开通，未核对（记录 id={}）", day, r.getId());
            } else {
                log.info("[TRADE] 每日对账 {}：已支付 {} 单 / 已关闭 {} 单，一致 {}，差异 {}（记录 id={}）",
                        day, r.getLocalPaidCount(), r.getLocalClosedCount(), r.getMatchedCount(),
                        r.getMismatchCount(), r.getId());
            }
        } catch (RuntimeException e) {
            // 超过单次上限等：这一天没有记录，列表里看得出缺口，手动分段补
            log.error("[TRADE] 每日对账 {} 失败，请到后台手动分段对账", day, e);
        }
    }
}
