package com.hengde.enterprise.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 爱心企业积分出参。
 *
 * @author hengde
 */
public final class EnterprisePointVOs {

    private EnterprisePointVOs() {
    }

    @Data
    @Schema(description = "企业积分总览")
    public static class Summary {
        private Long enterpriseId;
        @Schema(description = "当前余额＝全部流水之和")
        private Long balance;
        @Schema(description = "累计兑换入账")
        private Long totalExchanged;
        @Schema(description = "累计后台调整（通常为兑换企业权益的扣减）")
        private Long totalAdjusted;
    }

    @Data
    @Schema(description = "企业积分流水")
    public static class Record {
        private Long id;
        private Integer changeAmount;
        @Schema(description = "1兑换入账/2后台调整")
        private Integer sourceType;
        private String sourceLabel;
        @Schema(description = "兑换单 id（兑换入账）")
        private Long sourceId;
        private String remark;
        private LocalDateTime createTime;
    }
}
