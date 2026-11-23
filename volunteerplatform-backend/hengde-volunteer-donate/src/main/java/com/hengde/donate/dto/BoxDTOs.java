package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 装箱相关的三个小入参。三个都只有一两个字段，拆三个文件只会让人在目录里多跳两次。
 *
 * @author hengde
 */
public final class BoxDTOs {

    private BoxDTOs() {
    }

    /** 建一只新箱子（生成箱码）。 */
    @Data
    @Schema(description = "新建箱子")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Create {

        @Schema(description = "捐书活动 id（一只箱子只装同一个活动的物资）")
        @NotNull(message = "请选择活动")
        private Long campaignId;
    }

    /** 扫一个码（装箱 / 出箱时扫的是物品专属码）。 */
    @Data
    @Schema(description = "扫码")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Scan {

        @Schema(description = "扫到的码")
        @NotBlank(message = "码不能为空")
        private String code;
    }

    /** 送达受赠单位（Row 17 第 10 步）。 */
    @Data
    @Schema(description = "送达")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Deliver {

        @Schema(description = "受赠单位 id")
        @NotNull(message = "请选择受赠单位")
        private Long recipientOrgId;
    }
}
