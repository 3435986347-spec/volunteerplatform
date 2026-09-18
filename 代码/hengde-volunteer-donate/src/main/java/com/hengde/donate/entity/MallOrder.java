package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 积分兑换单（Row 8）。
 *
 * <p><b>{@link #goodsName} / {@link #specName} / {@link #points} 三项是下单时的快照</b>，
 * 不是外键的冗余。规格可改可删，事后要还原得出「当时买的是什么、花了多少分」——
 * 与勋章「附带积分取发起时快照而非审核时现读」、纸质证书「自提点存快照文本不是悬空 id」同源。
 * 删一个规格，历史订单不会变成空壳。</p>
 *
 * <p>⚠️ 实际扣了多少分<b>以 {@code point_record} 为准</b>，本表的 {@code points}
 * 只是下单那一刻的凭据；两者理应一致，不一致时账本是权威。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mall_order")
public class MallOrder extends BaseEntity {

    /** 对外单号 */
    private String orderNo;
    /** 兑换人 */
    private Long volunteerId;
    /** 商品 id */
    private Long goodsId;
    /** 规格 id */
    private Long specId;
    /** 下单时快照：商品名 */
    private String goodsName;
    /** 下单时快照：规格名 */
    private String specName;
    /**
     * 下单时快照：<b>实际扣除的积分</b>（卷批 V48 起）。退分金额以它为准，与 {@code point_record} 对得上。
     * 没用卷时等于 {@link #originalPoints}；用了兑换卷时为 0。
     */
    private Integer points;
    /** 下单时快照：规格标价积分（V48） */
    private Integer originalPoints;
    /** 所用的卷发放记录 id；退单时凭它 CAS 归还（V48） */
    private Long couponGrantId;
    /** 下单时快照：所用卷名称（V48） */
    private String couponName;
    /** 下单时快照：卷抵扣了多少积分（V48） */
    private Integer couponDeductPoints;
    /** 状态，见 {@code MallOrderStatus} */
    private Integer status;
    /** 领取方式，见 {@code MallDeliveryType} */
    private Integer deliveryType;
    /** 自提点快照·名称 */
    private String pickupSiteName;
    /** 自提点快照·地址 */
    private String pickupSiteAddr;
    /** 自提点快照·电话 */
    private String pickupSitePhone;
    /** 取货码，审核通过时生成 */
    private String pickupCode;
    /** 核销时间 */
    private LocalDateTime pickupTime;
    /**
     * 核销人 id——<b>管理员核销时是 admin_user.id，核销员核销时是 volunteer.id</b>，
     * 必须配合 {@link #pickupOperatorType} 读，否则两类账号同号时会认错人（V48）。
     */
    private Long pickupOperator;
    /** 核销人类型 1 管理员 / 2 核销员（志愿者）；未核销为 null，见 {@code PickupOperatorType}（V48） */
    private Integer pickupOperatorType;
    /** 审核人 admin_user.id */
    private Long reviewBy;
    /** 审核时间 */
    private LocalDateTime reviewTime;
    /** 驳回原因 */
    private String rejectReason;

    // ---- V58 商城快递批：现金与快递 ----

    /** 下单时快照：商品现金部分【分】 */
    private Integer goodsCashFen;
    /** 下单时快照：快递费【分】（计价器输出）；自提为 0 */
    private Integer shippingFeeFen;
    /** 快递费支付方式，见 {@code MallShippingPayType}；自提为 null */
    private Integer shippingPayType;
    /** 下单时快照：积分抵扣汇率（1 元折多少积分） */
    private Integer pointsPerYuan;
    /** 下单时快照：快递费折成的积分（已并入 points） */
    private Integer shippingPoints;
    /** 应付现金【分】 */
    private Integer payCashFen;
    /** 付款截止（仅待支付） */
    private LocalDateTime payExpireTime;
    /** 付款成功的交易单 id */
    private Long tradeOrderId;
    private LocalDateTime paidTime;
    /** 驳回后发起的现金退款单号（已受理） */
    private String cashRefundNo;
    /** 现金退款发起失败的原因 */
    private String cashRefundError;
    private String recvName;
    /** 收件电话（密文） */
    private String recvPhone;
    private String recvAddress;
    private String expressCode;
    private String expressCompany;
    private String expressNo;
    private LocalDateTime shipTime;
    private Long shipBy;
}
