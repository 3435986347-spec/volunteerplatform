package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 一件捐赠物资的录入（Row 17 第 3 步「志愿者录入捐赠物资资料」；后台「单独添加物品」同形）。
 *
 * @author hengde
 */
@Data
@Schema(description = "捐赠物资")
@JsonIgnoreProperties(ignoreUnknown = true)
public class DonateItemInputDTO {

    @Schema(description = "物资名称（可先按商品条码在条码库里查出来再填）")
    @NotBlank(message = "请填写物资名称")
    @Size(max = 255, message = "物资名称过长")
    private String name;

    @Schema(description = "物资类型 1课外书籍/2学习用品/3运动器材/9其他")
    @NotNull(message = "请选择物资类型")
    private Integer itemType;

    @Schema(description = "数量，默认 1")
    @Min(value = 1, message = "数量至少为 1")
    @Max(value = 9999, message = "单行数量不超过 9999")
    private Integer quantity;

    @Schema(description = "商品条码（ISBN 等），可空")
    @Size(max = 64, message = "商品条码过长")
    private String catalogBarcode;
}
