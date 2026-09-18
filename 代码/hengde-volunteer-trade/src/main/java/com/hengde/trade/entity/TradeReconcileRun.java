package com.hengde.trade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 一次对账的结果（V55）。<b>每一次都落库，跳过的也落</b>——理由见 V55 那张表的注释：
 * 对账的价值在于差异被人看到，只写日志等于没人看到。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trade_reconcile_run")
public class TradeReconcileRun extends BaseEntity {

    private LocalDateTime windowFrom;

    private LocalDateTime windowTo;

    /** 见 {@code TradeReconcileService.TRIGGER_*} */
    private Integer triggerType;

    /** 手动触发人 admin_user.id；定时为空 */
    private Long operatorId;

    /** 1 = 渠道未开通，什么都没有核对过 */
    private Integer skipped;

    private Integer localPaidCount;

    private Integer localClosedCount;

    private Integer matchedCount;

    private Integer mismatchCount;

    /** 差异明细（JSON 数组）；列表页不取 */
    private String mismatchesJson;
}
