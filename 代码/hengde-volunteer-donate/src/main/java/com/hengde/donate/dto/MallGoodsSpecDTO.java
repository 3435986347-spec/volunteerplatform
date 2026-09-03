package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 商品规格入参。
 *
 * @author hengde
 */
@Data
@Schema(description = "商品规格")
@JsonIgnoreProperties(ignoreUnknown = true)
public class MallGoodsSpecDTO {

    @Schema(description = "规格 id；新增时不传，修改时传表示保留该行")
    private Long id;

    @Schema(description = "规格名，如「500ml」「均码」")
    @NotBlank(message = "请填写规格名")
    @Size(max = 64, message = "规格名不超过 64 字")
    private String name;

    @Schema(description = "所需积分")
    @NotNull(message = "请填写所需积分")
    @Min(value = 1, message = "所需积分必须大于 0")
    private Integer points;

    @Schema(description = "库存")
    @NotNull(message = "请填写库存")
    @Min(value = 0, message = "库存不能为负")
    private Integer stock;

    @Schema(description = "排序")
    private Integer sort;
}
