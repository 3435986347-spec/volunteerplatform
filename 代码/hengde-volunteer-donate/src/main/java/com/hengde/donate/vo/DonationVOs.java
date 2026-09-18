package com.hengde.donate.vo;

import com.hengde.trade.vo.TradeVOs;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 捐款出参（V3 捐款批）。
 *
 * @author hengde
 */
public final class DonationVOs {

    private DonationVOs() {
    }

    @Data
    @Schema(description = "捐款记录")
    public static class Donation {
        private Long id;
        private String donationNo;
        private Integer bizType;
        private String bizTypeLabel;
        private Long projectId;
        private String projectTitle;
        private Long pairRecordId;
        private BigDecimal amount;
        private Integer status;
        private String statusLabel;
        private String remark;
        private LocalDateTime payExpireTime;
        private LocalDateTime paidTime;
        private String cancelReason;
        private Boolean needInvoice;
        private String invoiceTitle;
        private String invoiceTaxNo;
        private Integer invoiceStatus;
        private String invoiceStatusLabel;
        private String invoiceNo;
        private LocalDateTime refundTime;
        private String refundReason;
        private LocalDateTime createTime;
        @Schema(description = "捐款人 id（仅管理端）")
        private Long volunteerId;
        @Schema(description = "捐款人姓名（仅管理端）")
        private String volunteerName;
        @Schema(description = "付款成功的交易单 id（仅管理端；到收付页查流水、重试退款用）")
        private Long tradeOrderId;
        @Schema(description = "原路退款单号（仅管理端）")
        private String cashRefundNo;
        @Schema(description = "原路退款发起失败的原因（仅管理端；非空说明钱还没退出去）")
        private String cashRefundError;
    }

    @Data
    @Schema(description = "发起捐款的结果：捐款记录 + 唤起支付的参数")
    public static class Created {
        private Donation donation;
        private TradeVOs.Prepay prepay;
    }

    /**
     * 「我的捐赠记录」的一行（Row 33，V3 收尾批）：众筹的一次捐款或一次捐物。
     * 详情按 {@code kind} 分别走 {@code GET /v/donate/donations/{refId}} 与 {@code GET /v/donate/shipments/{refId}}（含物流轨迹）。
     */
    @Data
    @Schema(description = "我的捐赠记录（捐款 / 捐物合并）")
    public static class MyRecord {
        @Schema(description = "1捐款 / 2捐物")
        private Integer kind;
        private String kindLabel;
        @Schema(description = "捐款记录 id（kind=1）或运单 id（kind=2）")
        private Long refId;
        private Long projectId;
        private String projectTitle;
        @Schema(description = "捐款金额（仅捐款）")
        private BigDecimal amount;
        @Schema(description = "捐款：0待支付/1已到账/2已取消/3已退款；捐物：1已寄出/2已到货/3已核对/4已取消")
        private Integer status;
        private String statusLabel;
        @Schema(description = "快递公司（仅捐物）")
        private String expressCompany;
        @Schema(description = "快递单号（仅捐物）")
        private String expressNo;
        @Schema(description = "物资项数（仅捐物）")
        private Integer itemCount;
        @Schema(description = "最新一条物流轨迹（仅捐物；快照，可能为空）")
        private String trackLastContext;
        private LocalDateTime trackLastTime;
        @Schema(description = "捐款发起时间 / 寄出登记时间")
        private LocalDateTime recordTime;
    }

    /** 项目捐赠记录（Row 10「项目捐赠记录」）：公开展示，<b>姓名打码</b>、只列已到账的。 */
    @Data
    @Schema(description = "项目捐赠记录（公开）")
    public static class PublicRecord {
        @Schema(description = "捐款人（打码，如「张**」）")
        private String donorName;
        private BigDecimal amount;
        private String remark;
        private LocalDateTime paidTime;
    }
}
