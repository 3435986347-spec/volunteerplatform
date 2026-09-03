package com.hengde.donate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 「全部兑换记录」的公开出参（Row 8 C 要求展示<b>全部人的</b>兑换记录）。
 *
 * <p><b>刻意比 {@link MallOrderVO} 窄</b>：这是一份人人可见的公开流水，
 * 只放「谁 · 兑换了什么 · 什么时候」。<b>不含订单编号、不含取货码、不含审核与驳回信息</b>——
 * 取货码是柜台上的持有者凭据，公开列表里出现一次就等于把东西送给任何看见的人。</p>
 *
 * <p>姓名露全名，与活动详情的「报名详情」同口径（2026-06-27 用户已确认露完整姓名）。
 * 「公开到什么程度」本身仍待协会确认，见《协会待确认清单-v3》。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "公开兑换记录")
public class ExchangeRecordVO {

    @Schema(description = "兑换人姓名")
    private String volunteerName;

    @Schema(description = "商品名（下单时快照）")
    private String goodsName;

    @Schema(description = "商品规格（下单时快照）")
    private String specName;

    @Schema(description = "兑换时间")
    private LocalDateTime createTime;
}
