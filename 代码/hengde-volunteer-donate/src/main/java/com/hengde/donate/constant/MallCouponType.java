package com.hengde.donate.constant;

/**
 * 卷类型（与 V46 {@code mall_coupon.type} 一致）。
 *
 * <p>两种卷的名字来自 Row 8 F「如指定商品兑换卷、积分满减卷」，语义取《协会待确认清单-v3》④ 的默认：</p>
 * <ul>
 *   <li>{@link #EXCHANGE} 只能用于指定的那一件商品，<b>全额抵扣</b>所需积分——
 *       「兑换卷」字面就是拿卷换东西；抵一部分的是满减卷；</li>
 *   <li>{@link #DISCOUNT} 规格所需积分满门槛减固定积分，适用商品为空时全场通用。</li>
 * </ul>
 *
 * @author hengde
 */
public final class MallCouponType {

    private MallCouponType() {
    }

    /** 指定商品兑换卷：全额抵扣。 */
    public static final int EXCHANGE = 1;

    /** 积分满减卷：满 threshold 减 discount。 */
    public static final int DISCOUNT = 2;

    public static boolean isValid(Integer type) {
        return type != null && (type == EXCHANGE || type == DISCOUNT);
    }

    public static String labelOf(Integer type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case EXCHANGE -> "指定商品兑换卷";
            case DISCOUNT -> "积分满减卷";
            default -> "未知";
        };
    }
}
