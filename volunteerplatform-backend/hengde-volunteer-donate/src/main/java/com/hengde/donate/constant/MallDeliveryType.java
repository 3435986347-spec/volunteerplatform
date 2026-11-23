package com.hengde.donate.constant;

/**
 * 领取方式。
 *
 * <p>{@link #EXPRESS} 自商城快递批起可达：快递费由计价器算出、下单时快照，
 * 志愿者选择现金（走 trade）或积分抵扣（并入这张单的那一笔 EXCHANGE 流水）。
 * 总开关 {@code hengde.donate.mall.express.enabled} 默认关闭——快递费与折算汇率协会还没给（清单 ③）。</p>
 *
 * @author hengde
 */
public final class MallDeliveryType {

    private MallDeliveryType() {
    }

    /** 自提：现场扫取货码核销 */
    public static final int PICKUP = 1;
    /** 快递：商城快递批起开放 */
    public static final int EXPRESS = 2;

    public static boolean isValid(Integer t) {
        return t != null && (t == PICKUP || t == EXPRESS);
    }

    public static String labelOf(Integer t) {
        if (t == null) {
            return "";
        }
        return t == EXPRESS ? "快递" : "自提";
    }
}
