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
    /** 下单时快照：所需积分 */
    private Integer points;
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
    /** 核销人 admin_user.id */
    private Long pickupOperator;
    /** 审核人 admin_user.id */
    private Long reviewBy;
    /** 审核时间 */
    private LocalDateTime reviewTime;
    /** 驳回原因 */
    private String rejectReason;
}
