package com.hengde.trade.constant;

/**
 * 交易单 / 支付 / 退款的状态码与业务类型（与 V55 表注释一致）。
 *
 * <p><b>金额一律是「分」</b>：微信支付 APIv3 的单位就是分，换算（元 → 分）发生在调用方那一侧，
 * 且只发生在那一处。两套单位并存是对账时最难查的一类错。</p>
 *
 * @author hengde
 */
public final class TradeFlow {

    private TradeFlow() {
    }

    // ================= 业务类型 =================

    /** 商城快递费（Row 8，商城快递批接）。 */
    public static final int BIZ_MALL_SHIPPING = 1;
    /** 众筹捐款（Row 16，捐款批接）。 */
    public static final int BIZ_CROWDFUND = 2;
    /** 助学结对捐款（Row 10，捐款批接）。 */
    public static final int BIZ_PAIR = 3;

    // ================= 交易单状态 =================

    /** 待支付。 */
    public static final int ORDER_PENDING = 0;
    /** 已支付。 */
    public static final int ORDER_PAID = 1;
    /** 已关闭（超时或后台关单）。 */
    public static final int ORDER_CLOSED = 2;
    /** 已全额退款。 */
    public static final int ORDER_REFUNDED = 3;
    /** 已部分退款。 */
    public static final int ORDER_PARTIAL_REFUNDED = 4;

    // ================= 支付流水来源 =================

    /** 回调（最快的一条，但<b>不是唯一的一条</b>——事件会丢，见 V3规划 D2）。 */
    public static final int SOURCE_CALLBACK = 1;
    /** 主动查单（本地与微信不一致时以它为准）。 */
    public static final int SOURCE_QUERY = 2;
    /** 分钟级扫描任务。 */
    public static final int SOURCE_SCAN = 3;

    // ================= 退款状态 =================

    public static final int REFUND_PROCESSING = 0;
    public static final int REFUND_SUCCESS = 1;
    public static final int REFUND_FAILED = 2;
    public static final int REFUND_CLOSED = 3;

    /** 渠道：目前只有微信小程序支付。 */
    public static final int CHANNEL_WECHAT = 1;

    public static boolean isValidBizType(Integer t) {
        return t != null && (t == BIZ_MALL_SHIPPING || t == BIZ_CROWDFUND || t == BIZ_PAIR);
    }

    /** 钱已经进来过：已支付 / 部分退款 / 已退款。 */
    public static boolean isPaidLike(Integer status) {
        return status != null && (status == ORDER_PAID || status == ORDER_PARTIAL_REFUNDED || status == ORDER_REFUNDED);
    }

    /** 终态：不会再变的状态，扫描任务不必再捞。 */
    public static boolean isTerminal(Integer status) {
        return status != null && (status == ORDER_CLOSED || status == ORDER_REFUNDED);
    }

    public static String bizLabel(Integer t) {
        if (t == null) {
            return "";
        }
        return switch (t) {
            case BIZ_MALL_SHIPPING -> "积分商城（现金部分）";
            case BIZ_CROWDFUND -> "众筹捐款";
            case BIZ_PAIR -> "助学结对捐款";
            default -> "未知";
        };
    }

    public static String orderLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case ORDER_PENDING -> "待支付";
            case ORDER_PAID -> "已支付";
            case ORDER_CLOSED -> "已关闭";
            case ORDER_REFUNDED -> "已退款";
            case ORDER_PARTIAL_REFUNDED -> "部分退款";
            default -> "未知";
        };
    }

    public static String refundLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case REFUND_PROCESSING -> "处理中";
            case REFUND_SUCCESS -> "退款成功";
            case REFUND_FAILED -> "退款失败";
            case REFUND_CLOSED -> "已关闭";
            default -> "未知";
        };
    }

    /** 金额展示：分 → 元（只用于出参，库里恒为分）。 */
    public static String yuan(Integer fen) {
        if (fen == null) {
            return "0.00";
        }
        return java.math.BigDecimal.valueOf(fen).movePointLeft(2).toPlainString();
    }
}
