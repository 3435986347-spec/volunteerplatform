package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 卷发放记录（V46 {@code mall_coupon_grant}，即「我的卷」）。
 *
 * <p><b>条款全部是发放时的快照</b>（名称 / 类型 / 适用商品 / 门槛 / 抵扣 / 有效期）：
 * 发出之后卷定义再怎么改，都不追溯改写这张已经在志愿者手里的卷。
 * 用卷时的判定一律读这里，不读 {@link MallCoupon}。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mall_coupon_grant")
public class MallCouponGrant extends BaseEntity {

    private Long couponId;
    private Long volunteerId;

    /** 发放批次幂等键 */
    private String requestId;

    private String couponName;
    private Integer type;
    private Long goodsId;
    private Integer thresholdPoints;
    private Integer discountPoints;
    private LocalDateTime validStart;
    private LocalDateTime expireTime;

    /** 0 未使用 / 1 已使用 / 2 已作废；「已过期」现算不落库 */
    private Integer status;

    private Long usedOrderId;
    private LocalDateTime usedTime;
    private Long grantBy;
    private Long revokeBy;
    private LocalDateTime revokeTime;
    private String revokeReason;
}
