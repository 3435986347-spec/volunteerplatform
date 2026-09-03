package com.hengde.donate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品评价出参。
 *
 * <p>发表人只露姓名，与活动留言同口径（经 {@code VolunteerQueryService.listNamesByIds}
 * 只 select 姓名列、不解密手机号）。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "商品评价")
public class MallReviewVO {

    @Schema(description = "评价 id")
    private Long id;

    @Schema(description = "商品 id")
    private Long goodsId;

    @Schema(description = "商品名（快照，「我的评价」列表要显示评的是什么）")
    private String goodsName;

    @Schema(description = "规格名（快照）")
    private String specName;

    @Schema(description = "评分 1~5")
    private Integer rating;

    @Schema(description = "评价内容")
    private String content;

    @Schema(description = "评价人姓名")
    private String volunteerName;

    @Schema(description = "评价时间")
    private LocalDateTime createTime;
}
