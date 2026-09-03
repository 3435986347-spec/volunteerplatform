package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 下单兑换入参。
 *
 * <p><b>没有数量字段</b>——一单一件是本批的显式取舍（Row 8 的兑换与「我的兑换」都没有数量控件），
 * 理由与「要加数量得同时改哪四处」记在 {@code 文档/v3/V3规划.md} 的商城批一节。</p>
 *
 * <p><b>也没有志愿者 id</b>：一律取自登录态，与积分中心同一纪律。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "下单兑换")
@JsonIgnoreProperties(ignoreUnknown = true)
public class MallOrderPlaceDTO {

    @Schema(description = "规格 id（库存与积分都挂在规格上）")
    @NotNull(message = "请选择商品规格")
    private Long specId;
}
