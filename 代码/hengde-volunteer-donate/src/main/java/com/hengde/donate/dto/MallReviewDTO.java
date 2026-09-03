package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 商品评价入参。
 *
 * <p><b>没有 goodsId</b>——评的是哪个商品由订单说了算，由服务端从订单上取。
 * 让入参带 goodsId 就等于「拿着自己的一张兑换单去评别人的商品」。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "商品评价")
@JsonIgnoreProperties(ignoreUnknown = true)
public class MallReviewDTO {

    @Schema(description = "评分 1~5")
    @NotNull(message = "请打分")
    @Min(value = 1, message = "评分为 1~5")
    @Max(value = 5, message = "评分为 1~5")
    private Integer rating;

    @Schema(description = "评价内容")
    @Size(max = 512, message = "评价内容不超过 512 字")
    private String content;
}
