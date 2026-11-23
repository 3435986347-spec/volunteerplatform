package com.hengde.trade.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 退款流水（V55）。退款是<b>异步</b>的：渠道受理只代表「收到了」，成不成以回调 / 查询为准。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trade_refund")
public class TradeRefund extends BaseEntity {

    private Long tradeOrderId;

    /** 我方退款单号，全局唯一且不复用 */
    private String outRefundNo;

    /** 渠道退款单号 */
    private String refundId;

    /** 退款金额【分】 */
    private Integer amount;

    /** 见 {@code TradeFlow.REFUND_*} */
    private Integer status;

    /** 退款原因（后台必填，给对账与纠纷看） */
    private String reason;

    private Long operatorId;

    private LocalDateTime successTime;

    /** 原始报文（解密后）留底；理由与 {@code TradePayment.rawJson} 相同 */
    @TableField(select = false)
    private String rawJson;
}
