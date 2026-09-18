package com.hengde.donate.constant;

/**
 * 兑换单状态。
 *
 * <p><b>下单即扣分，驳回/取消退分</b>——与奖惩「审核通过才入账」<b>刻意相反</b>：
 * 那里是罚，这里是用户主动消费，积分与库存都必须在下单那一刻占位，
 * 否则同一笔积分能下十张待审单。<b>这条差异是有意的，不要为了「统一」而改掉。</b></p>
 *
 * @author hengde
 */
public final class MallOrderStatus {

    private MallOrderStatus() {
    }

    /** 待审核：已扣分、已占库存 */
    public static final int PENDING = 0;
    /** 已通过，待领取：取货码在此刻生成 */
    public static final int READY = 1;
    /** 已驳回：退分 + 还库存 */
    public static final int REJECTED = 2;
    /** 已领取：核销完成，终态 */
    public static final int PICKED = 3;
    /** 已取消：用户自行取消，退分 + 还库存 */
    public static final int CANCELLED = 4;
    /**
     * 待支付（商城快递批）：有现金要付的单。<b>积分、库存、卷在下单那一刻就已占住</b>——与「下单即扣分」同一口径；
     * 付款成功推进到待审核，超过付款截止没付的由补偿任务取消并全部归还。
     */
    public static final int AWAITING_PAYMENT = 5;
    /** 已发货（商城快递批）：快递单审核通过后由后台登记快递单号；收件人确认或到期自动确认后落「已领取」。 */
    public static final int SHIPPED = 6;

    /**
     * 状态中文名，供出参直接展示。
     *
     * @param status 状态码
     * @return 中文名；未知码返回「其他」
     */
    public static String labelOf(Integer status) {
        if (status == null) {
            return "其他";
        }
        return switch (status) {
            case PENDING -> "待审核";
            case READY -> "待领取";
            case REJECTED -> "已驳回";
            case PICKED -> "已领取";
            case CANCELLED -> "已取消";
            case AWAITING_PAYMENT -> "待支付";
            case SHIPPED -> "已发货";
            default -> "其他";
        };
    }

    /**
     * 带领取方式的中文名：快递单的「已通过」叫「待发货」、「已领取」叫「已签收」——
     * 对快递单说「待领取」会让人以为要去哪儿取。
     */
    public static String labelOf(Integer status, Integer deliveryType) {
        boolean express = deliveryType != null && deliveryType == MallDeliveryType.EXPRESS;
        if (express && status != null && status == READY) {
            return "待发货";
        }
        if (express && status != null && status == PICKED) {
            return "已签收";
        }
        return labelOf(status);
    }

    /** 是否为「已退款」终态——这两种状态下积分与库存都已归还，不得重复归还 */
    public static boolean isRefunded(Integer status) {
        return status != null && (status == REJECTED || status == CANCELLED);
    }
}
