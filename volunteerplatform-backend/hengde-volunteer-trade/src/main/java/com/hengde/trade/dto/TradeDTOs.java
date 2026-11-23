package com.hengde.trade.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 收付的入参（V3 trade 批）。
 *
 * <p><b>金额一律是「分」</b>：元 → 分的换算在调用方那一侧完成，且只在那一处。</p>
 *
 * @author hengde
 */
public final class TradeDTOs {

    private TradeDTOs() {
    }

    /**
     * 下单。<b>由别的领域在服务层调用</b>（商城快递费、众筹捐款、结对捐款），不是 HTTP 入参——
     * trade 模块不放 controller（V3规划 D1）。
     */
    @Data
    @Schema(description = "下单")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CreateOrder {

        @Schema(description = "业务类型，见 TradeFlow.BIZ_*")
        @NotNull(message = "业务类型不能为空")
        private Integer bizType;

        @Schema(description = "业务单据号（兑换单 id、结对登记 id 等）")
        @NotBlank(message = "业务单据号不能为空")
        @Size(max = 64, message = "业务单据号过长")
        private String bizNo;

        @Schema(description = "付款人 volunteer.id")
        private Long volunteerId;

        @Schema(description = "付款人微信 openid（唤起小程序支付要用）")
        private String payerOpenid;

        @Schema(description = "商品 / 项目名（落库快照，事后改名不影响已下的单）")
        @NotBlank(message = "商品描述不能为空")
        @Size(max = 128, message = "商品描述过长")
        private String subject;

        @Schema(description = "金额【分】")
        @NotNull(message = "金额不能为空")
        @Min(value = 1, message = "金额须大于 0")
        private Integer amountFen;

        @Schema(description = "备注")
        @Size(max = 512, message = "备注过长")
        private String remark;

        /**
         * 业务侧的付款截止时刻，可空。<b>交易单的过期时刻取「TTL 算出来的」与它两者中更早的那个</b>。
         *
         * <p>理由：业务单据有自己的占位时限（商城待支付的单占着库存与积分），用户隔了十分钟才点支付时，
         * 交易单若再给满 15 分钟，就能在业务单据已经超时之后付进来——钱收了、单子却要被超时取消。</p>
         */
        @Schema(hidden = true)
        private java.time.LocalDateTime expireAt;
    }

    /** 退款（后台发起）。不填金额=整单退（默认粒度，见《清单-v3》⑭）。 */
    @Data
    @Schema(description = "退款")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Refund {

        @Schema(description = "退款金额【分】；不填=整单退")
        @Min(value = 1, message = "退款金额须大于 0")
        private Integer amountFen;

        @Schema(description = "退款原因（必填，给对账与纠纷看）")
        @NotBlank(message = "请填写退款原因")
        @Size(max = 512, message = "退款原因不超过 512 字")
        private String reason;
    }
}
