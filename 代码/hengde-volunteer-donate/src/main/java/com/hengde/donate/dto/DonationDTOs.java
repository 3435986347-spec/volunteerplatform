package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 捐款入参（V3 捐款批）。
 *
 * @author hengde
 */
public final class DonationDTOs {

    private DonationDTOs() {
    }

    /** 发票信息（清单⑦：只预留字段，不接税务）。 */
    @Data
    public static class InvoiceFields {
        @Schema(description = "是否需要发票")
        private Boolean needInvoice;

        @Schema(description = "发票抬头（需要发票时必填）")
        @Size(max = 128, message = "发票抬头过长")
        private String invoiceTitle;

        @Schema(description = "纳税人识别号（单位抬头填；个人可空）")
        @Size(max = 32, message = "纳税人识别号过长")
        private String invoiceTaxNo;

        @Schema(description = "捐款留言")
        @Size(max = 255, message = "留言不超过 255 字")
        private String remark;

        @Schema(description = "小程序 wx.login 的临时 code（服务端现换付款人 openid）")
        @NotBlank(message = "缺少微信登录凭证")
        private String code;
    }

    /** 众筹捐款：金额由捐款人自己填。 */
    @Data
    @Schema(description = "众筹捐款")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CrowdfundDonate extends InvoiceFields {
        @Schema(description = "捐款金额（元，最多两位小数）")
        @NotNull(message = "请填写捐款金额")
        @DecimalMin(value = "0.01", message = "捐款金额须大于 0")
        @Digits(integer = 10, fraction = 2, message = "捐款金额最多两位小数")
        private BigDecimal amount;
    }

    /**
     * 结对捐款：<b>不收金额</b>——付的是「认捐额 − 已付」，认捐额在登记结对时就定了（指定金额 / 全款）。
     * 让人在付款时再填一次，两个数对不上时该听哪个就说不清了。
     */
    @Data
    @Schema(description = "结对捐款")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PairDonate extends InvoiceFields {
    }

    @Data
    @Schema(description = "重新发起付款")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Pay {
        @Schema(description = "小程序 wx.login 的临时 code")
        @NotBlank(message = "缺少微信登录凭证")
        private String code;
    }

    @Data
    @Schema(description = "退款")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Refund {
        @Schema(description = "退款原因（必填）")
        @NotBlank(message = "请填写退款原因")
        @Size(max = 512, message = "退款原因不超过 512 字")
        private String reason;
    }

    @Data
    @Schema(description = "登记开票")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Invoice {
        @Schema(description = "发票号")
        @NotBlank(message = "请填写发票号")
        @Size(max = 64, message = "发票号过长")
        private String invoiceNo;
    }
}
