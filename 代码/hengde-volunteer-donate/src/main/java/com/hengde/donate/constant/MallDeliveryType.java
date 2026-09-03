package com.hengde.donate.constant;

/**
 * 领取方式。
 *
 * <p>⚠️ {@link #EXPRESS} 的取值已占位，但<b>本批不可达</b>——快递要计价器与 trade 收付，
 * 属商城快递批。下单入口显式拒绝该取值，而不是留一条走不通的分支。</p>
 *
 * @author hengde
 */
public final class MallDeliveryType {

    private MallDeliveryType() {
    }

    /** 自提：现场扫取货码核销 */
    public static final int PICKUP = 1;
    /** 快递：商城快递批才开放 */
    public static final int EXPRESS = 2;
}
