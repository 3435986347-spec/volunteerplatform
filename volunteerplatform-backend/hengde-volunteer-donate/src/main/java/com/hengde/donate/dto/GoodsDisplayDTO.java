package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 排序 / 隐藏（纯展示，<b>不触发重审</b>）。
 *
 * <p>两个字段都可空，只改传了的那个。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "排序与隐藏")
@JsonIgnoreProperties(ignoreUnknown = true)
public class GoodsDisplayDTO {

    @Schema(description = "排序，小的在前；不传=不改")
    private Integer sort;

    @Schema(description = "隐藏 0否/1是（Row 8 F「商品隐藏功能」）；不传=不改")
    @Min(value = 0, message = "隐藏标记只能是 0 或 1")
    @Max(value = 1, message = "隐藏标记只能是 0 或 1")
    private Integer hidden;
}
