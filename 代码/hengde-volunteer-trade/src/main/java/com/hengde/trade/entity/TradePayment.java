package com.hengde.trade.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 支付流水（V55）。{@code transaction_id} 是<b>幂等键</b>：重复回调、主动查单、扫描任务
 * 三条路都往这里写，靠 {@code uk_transaction} 挡重复，不靠「先查再插」。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trade_payment")
public class TradePayment extends BaseEntity {

    private Long tradeOrderId;

    /** 渠道支付单号——幂等键 */
    private String transactionId;

    /** 实付金额【分】。<b>必须与交易单比对，不符一律拒绝</b> */
    private Integer amount;

    private String payerOpenid;

    /** 渠道返回的支付完成时刻 */
    private LocalDateTime successTime;

    /**
     * 原始报文（解密后）留底：对账与纠纷时唯一能复盘的东西。
     *
     * <p>{@code select = false}：它是 MEDIUMTEXT，列表查询不该把它一起拖出来，
     * 需要时单独读（同 {@code donate_shipment.track_json} 的处理）。</p>
     */
    @TableField(select = false)
    private String rawJson;

    /** 见 {@code TradeFlow.SOURCE_*}：这一条是回调写的、查单写的，还是扫描任务写的 */
    private Integer source;
}
