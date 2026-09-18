package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 捐款记录（V59）：众筹捐款与结对捐款共用一张表，按 {@code bizType} 分流。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("donate_donation")
public class DonateDonation extends BaseEntity {

    private String donationNo;
    /** 见 {@code DonationFlow.BIZ_*} */
    private Integer bizType;
    private Long projectId;
    private Long pairRecordId;
    private Long volunteerId;
    private String projectTitle;
    /** 元 */
    private BigDecimal amount;
    /** 分（进 trade 的那个数） */
    private Integer amountFen;
    /** 见 {@code DonationFlow} */
    private Integer status;
    private String remark;
    private LocalDateTime payExpireTime;
    private Long tradeOrderId;
    private LocalDateTime paidTime;
    private String cancelReason;
    private Integer needInvoice;
    private String invoiceTitle;
    private String invoiceTaxNo;
    private Integer invoiceStatus;
    private String invoiceNo;
    private LocalDateTime invoiceTime;
    private Long invoiceBy;
    private LocalDateTime refundTime;
    private Long refundBy;
    private String refundReason;
    private String cashRefundNo;
    private String cashRefundError;
}
