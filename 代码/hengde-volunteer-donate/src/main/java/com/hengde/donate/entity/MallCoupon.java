package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 卷定义（V46 {@code mall_coupon}）。
 *
 * <p>改这里的条款<b>只影响之后发出的卷</b>——已发出的卷在 {@link MallCouponGrant} 上有全套快照。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mall_coupon")
public class MallCoupon extends BaseEntity {

    /** 卷名称 */
    private String name;

    /** 1 指定商品兑换卷 / 2 积分满减卷，见 {@link com.hengde.donate.constant.MallCouponType} */
    private Integer type;

    /** 适用商品：兑换卷必填；满减卷为 null 表示全场通用 */
    private Long goodsId;

    /** 满减门槛（按规格所需积分） */
    private Integer thresholdPoints;

    /** 满减抵扣积分；兑换卷为 null（全额抵扣） */
    private Integer discountPoints;

    /** 有效期起（含） */
    private LocalDateTime validStart;

    /** 有效期止（不含） */
    private LocalDateTime validEnd;

    /** 1 启用 / 0 停用——停用只挡新发放 */
    private Integer status;

    /** 使用说明 */
    private String description;

    /** 创建人 admin_user.id */
    private Long createBy;
}
