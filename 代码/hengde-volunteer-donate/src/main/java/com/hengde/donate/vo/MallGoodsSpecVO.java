package com.hengde.donate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 规格出参。
 *
 * @author hengde
 */
@Data
@Schema(description = "商品规格")
public class MallGoodsSpecVO {

    @Schema(description = "规格 id")
    private Long id;

    @Schema(description = "规格名")
    private String name;

    @Schema(description = "所需积分")
    private Integer points;

    @Schema(description = "库存")
    private Integer stock;

    @Schema(description = "排序")
    private Integer sort;
}
