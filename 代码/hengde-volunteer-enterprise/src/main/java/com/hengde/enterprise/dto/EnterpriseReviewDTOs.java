package com.hengde.enterprise.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 赞助商评价入参。
 *
 * @author hengde
 */
public final class EnterpriseReviewDTOs {

    private EnterpriseReviewDTOs() {
    }

    @Data
    @Schema(description = "评价赞助商（凭一张自己的、已领取的、这家企业赞助的兑换单）")
    public static class Create {
        @NotNull(message = "请指定兑换单")
        private Long orderId;

        @NotNull(message = "请打分")
        @Min(value = 1, message = "评分是 1~5")
        @Max(value = 5, message = "评分是 1~5")
        private Integer rating;

        @Size(max = 500, message = "评价不超过 500 字")
        private String content;
    }

    @Data
    @Schema(description = "屏蔽评价（Row 74「删除、屏蔽功能」）")
    public static class Hide {
        @Size(max = 255, message = "原因不超过 255 字")
        private String reason;
    }
}
