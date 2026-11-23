package com.hengde.donate.constant;

/**
 * 兑换单核销人类型（与 V48 {@code mall_order.pickup_operator_type} 一致）。
 *
 * <p>卷批起核销人不再只有管理员：核销员（志愿者）也能在小程序扫码核销。
 * {@code pickup_operator} 一列同时装 {@code admin_user.id} 与 {@code volunteer.id}，
 * <b>不带类型就分不清是谁</b>——与活动违规「记录人跨域同号错认」是同一类问题。</p>
 *
 * @author hengde
 */
public final class PickupOperatorType {

    private PickupOperatorType() {
    }

    /** 管理员（后台 {@code POST /a/donate/orders/verify}）。 */
    public static final int ADMIN = 1;

    /** 核销员（志愿者，小程序 {@code POST /v/donate/orders/verify}）。 */
    public static final int VERIFIER = 2;

    /** 快递单：收件人本人在小程序确认收货（{@code pickup_operator} 是本人 volunteer.id）。商城快递批起。 */
    public static final int RECIPIENT = 3;

    /** 快递单：发货满若干天系统自动确认收货（{@code pickup_operator} 为空）。商城快递批起。 */
    public static final int SYSTEM = 4;

    public static String labelOf(Integer type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case ADMIN -> "管理员";
            case VERIFIER -> "核销员";
            case RECIPIENT -> "本人确认收货";
            case SYSTEM -> "系统自动确认收货";
            default -> "未知";
        };
    }
}
