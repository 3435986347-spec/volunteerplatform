package com.hengde.donate.constant;

/**
 * 捐款（V3 捐款批）的状态码与业务类型。
 *
 * <p>业务类型与 trade 的 {@code TradeFlow.BIZ_*} <b>同码</b>（2 众筹 / 3 结对）：同一个数在两边指同一件事，
 * 交易单的 {@code biz_no} 就是捐款记录 id。</p>
 *
 * @author hengde
 */
public final class DonationFlow {

    private DonationFlow() {
    }

    /** 众筹捐款（与 TradeFlow.BIZ_CROWDFUND 同码） */
    public static final int BIZ_CROWDFUND = 2;
    /** 结对捐款（与 TradeFlow.BIZ_PAIR 同码） */
    public static final int BIZ_PAIR = 3;

    public static final int AWAITING_PAYMENT = 0;
    /** 已到账：只有这一档计入已筹金额 */
    public static final int PAID = 1;
    public static final int CANCELLED = 2;
    public static final int REFUNDED = 3;

    public static final int INVOICE_NONE = 0;
    public static final int INVOICE_PENDING = 1;
    public static final int INVOICE_ISSUED = 2;

    public static boolean isValidBiz(Integer t) {
        return t != null && (t == BIZ_CROWDFUND || t == BIZ_PAIR);
    }

    public static String bizLabel(Integer t) {
        if (t == null) {
            return "";
        }
        return switch (t) {
            case BIZ_CROWDFUND -> "众筹捐款";
            case BIZ_PAIR -> "结对捐款";
            default -> "未知";
        };
    }

    public static String statusLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case AWAITING_PAYMENT -> "待支付";
            case PAID -> "已到账";
            case CANCELLED -> "已取消";
            case REFUNDED -> "已退款";
            default -> "未知";
        };
    }

    public static String invoiceLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case INVOICE_NONE -> "不需要";
            case INVOICE_PENDING -> "待开票";
            case INVOICE_ISSUED -> "已开票";
            default -> "未知";
        };
    }
}
