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
            default -> "其他";
        };
    }

    /** 是否为「已退款」终态——这两种状态下积分与库存都已归还，不得重复归还 */
    public static boolean isRefunded(Integer status) {
        return status != null && (status == REJECTED || status == CANCELLED);
    }
}
