package com.hengde.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 收付的出参（V3 trade 批）。金额同时给「分」与「元」：库里与渠道都是分，展示要元，
 * <b>换算只发生在这里</b>，前端不要再自己除以 100。
 *
 * @author hengde
 */
public final class TradeVOs {

    private TradeVOs() {
    }

    /** 下单结果：交易单 + 唤起支付所需的参数。 */
    @Data
    @Schema(description = "下单结果")
    public static class Prepay {
        private Long tradeOrderId;
        private String outTradeNo;
        private Integer amountFen;
        private String amountYuan;
        private LocalDateTime expireTime;
        @Schema(description = "小程序唤起支付的参数；渠道未开通时为空")
        private String prepayId;
        private String paySign;
        private String nonceStr;
        private String timeStamp;
        private String signType;
    }

    /** 交易单。 */
    @Data
    @Schema(description = "交易单")
    public static class Order {
        private Long id;
        private String outTradeNo;
        private Integer bizType;
        private String bizTypeLabel;
        private String bizNo;
        private Long volunteerId;
        private String subject;
        private Integer amountFen;
        private String amountYuan;
        private Integer refundedFen;
        private String refundedYuan;
        private Integer status;
        private String statusLabel;
        private Integer channel;
        private LocalDateTime expireTime;
        private LocalDateTime payTime;
        private LocalDateTime closeTime;
        private String transactionId;
        private String remark;
        private LocalDateTime createTime;
        @Schema(description = "支付流水（仅详情返回）")
        private List<Payment> payments = new ArrayList<>();
        @Schema(description = "退款流水（仅详情返回）")
        private List<Refund> refunds = new ArrayList<>();
    }

    /** 一条支付流水。 */
    @Data
    @Schema(description = "支付流水")
    public static class Payment {
        private Long id;
        private String transactionId;
        private Integer amountFen;
        private String amountYuan;
        private String payerOpenid;
        private LocalDateTime successTime;
        @Schema(description = "来源 1回调/2主动查单/3扫描任务")
        private Integer source;
        private LocalDateTime createTime;
    }

    /** 一条退款流水。 */
    @Data
    @Schema(description = "退款流水")
    public static class Refund {
        private Long id;
        private String outRefundNo;
        private String refundId;
        private Integer amountFen;
        private String amountYuan;
        private Integer status;
        private String statusLabel;
        private String reason;
        private LocalDateTime successTime;
        private LocalDateTime createTime;
    }

    /**
     * 对账结果（D2 的第四道）。
     *
     * <p><b>「一致」不是靠本地自己数出来的</b>：逐单拿渠道的查单结果比对，
     * 不一致的逐条列出来给人看——对账的价值正在于此，报一个「全部正常」没有意义。</p>
     */
    @Data
    @Schema(description = "对账结果")
    public static class Reconciliation {
        @Schema(description = "对账记录 id（每一次对账都落库）")
        private Long id;
        private LocalDateTime from;
        private LocalDateTime to;
        @Schema(description = "触发方式 1每日定时/2后台手动")
        private Integer triggerType;
        private String triggerLabel;
        @Schema(description = "手动触发人 admin_user.id；定时为空")
        private Long operatorId;
        @Schema(description = "本地记为已支付（含已退款）的单数")
        private int localPaidCount;
        @Schema(description = "本地已关闭的单数——「钱付进了已关闭的单」只会出现在这一侧")
        private int localClosedCount;
        @Schema(description = "核对通过的单数")
        private int matchedCount;
        @Schema(description = "差异条数")
        private int mismatchCount;
        @Schema(description = "渠道未开通时为 true——此时**没有核对过任何东西**，别把它读成「一致」")
        private boolean skipped;
        @Schema(description = "差异明细；列表接口不返回（按 id 取详情）")
        private List<Mismatch> mismatches = new ArrayList<>();
        private LocalDateTime createTime;
    }

    /** 一条对不上的记录。 */
    @Data
    @Schema(description = "对账差异")
    public static class Mismatch {
        private String outTradeNo;
        private Integer localStatus;
        private String localStatusLabel;
        private Integer localAmountFen;
        @Schema(description = "渠道侧是否已支付；查不到为 false")
        private boolean remotePaid;
        private Integer remoteAmountFen;
        private String note;
    }
}
