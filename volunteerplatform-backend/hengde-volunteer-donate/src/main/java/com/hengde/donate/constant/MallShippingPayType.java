package com.hengde.donate.constant;

/**
 * 快递费支付方式（Row 8 C「志愿者可以选择支付快递费，也可以选择积分抵扣」）。
 *
 * @author hengde
 */
public final class MallShippingPayType {

    private MallShippingPayType() {
    }

    /** 现金：并进这张单的应付现金，走 trade（微信支付）。 */
    public static final int CASH = 1;

    /** 积分抵扣：按下单时快照的汇率折成积分，并进这张单的那一笔 EXCHANGE 流水。 */
    public static final int POINTS = 2;

    public static boolean isValid(Integer t) {
        return t != null && (t == CASH || t == POINTS);
    }

    public static String labelOf(Integer t) {
        if (t == null) {
            return "";
        }
        return switch (t) {
            case CASH -> "现金支付";
            case POINTS -> "积分抵扣";
            default -> "未知";
        };
    }
}
