package com.hengde.trade.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 交易单（V55）。
 *
 * <p><b>金额是「分」</b>（{@link #amount} / {@link #refundedAmount}）：微信 APIv3 的单位就是分，
 * 换算只发生在调用方那一侧。生成列 {@code active_biz_key} 由数据库计算，应用不可写。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trade_order")
public class TradeOrder extends BaseEntity {

    /** 我方单号（给微信的 out_trade_no），全局唯一且不复用 */
    private String outTradeNo;

    /** 见 {@code TradeFlow.BIZ_*} */
    private Integer bizType;

    /** 业务单据号（兑换单 id、结对登记 id 等），由调用方给 */
    private String bizNo;

    private Long volunteerId;

    /** 商品 / 项目名快照——事后改名不影响已下的单 */
    private String subject;

    /** 金额【分】 */
    private Integer amount;

    /** 已退金额【分】 */
    private Integer refundedAmount;

    /** 见 {@code TradeFlow.ORDER_*} */
    private Integer status;

    /** 见 {@code TradeFlow.CHANNEL_*} */
    private Integer channel;

    private LocalDateTime expireTime;

    /** 支付成功时刻（以渠道返回的 success_time 为准，不用本地时钟） */
    private LocalDateTime payTime;

    private LocalDateTime closeTime;

    /** 渠道支付单号 */
    private String transactionId;

    private String remark;

    private Long createBy;

    /**
     * 「活」交易单的业务键，<b>由数据库生成列计算</b>，应用不可写（写它 INSERT 直接报错）。
     *
     * <p>待支付 / 已支付时占位，关闭或全额退款后释放——<b>关单之后必须能重新下单</b>，
     * 而微信要求换新的 out_trade_no。</p>
     */
    @TableField(value = "active_biz_key",
            insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER)
    private String activeBizKey;
}
