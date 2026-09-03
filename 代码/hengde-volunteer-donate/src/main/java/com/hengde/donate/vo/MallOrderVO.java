package com.hengde.donate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 兑换单出参（Row 8 C「我的兑换」：订单编号、商品名字、商品规格、赞助商家、状态、评价、审核状态）。
 *
 * <p><b>商品名 / 规格名 / 所需积分取订单上的快照，不回查商品表</b>——
 * 规格改了名、涨了价、被删了，历史订单仍须还原得出当时的样子。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "兑换单")
public class MallOrderVO {

    @Schema(description = "兑换单 id")
    private Long id;

    @Schema(description = "订单编号")
    private String orderNo;

    @Schema(description = "商品 id（用于跳详情；展示一律用下面的快照）")
    private Long goodsId;

    @Schema(description = "商品名（下单时快照）")
    private String goodsName;

    @Schema(description = "商品规格（下单时快照）")
    private String specName;

    @Schema(description = "所需积分（下单时快照）")
    private Integer points;

    @Schema(description = "状态码 0待审核/1已通过待领取/2已驳回/3已领取/4已取消")
    private Integer status;

    @Schema(description = "状态中文名")
    private String statusLabel;

    @Schema(description = "领取方式 1自提（2快递属商城快递批，本批不可达）")
    private Integer deliveryType;

    @Schema(description = "自提点名称（审核通过时快照）")
    private String pickupSiteName;

    @Schema(description = "自提点地址")
    private String pickupSiteAddr;

    @Schema(description = "自提点电话")
    private String pickupSitePhone;

    /** 仅「已通过待领取」时下发；已领取 / 已驳回 / 已取消都不再给。 */
    @Schema(description = "取货码，仅待领取时返回")
    private String pickupCode;

    /** 取货码的 Code128 条码图，供小程序直接展示给柜台扫描；仅详情接口返回。 */
    @Schema(description = "取货码条码图 data URL，仅详情且待领取时返回")
    private String pickupBarcode;

    @Schema(description = "核销时间")
    private LocalDateTime pickupTime;

    @Schema(description = "驳回原因")
    private String rejectReason;

    @Schema(description = "下单时间")
    private LocalDateTime createTime;

    @Schema(description = "是否已评价（Row 8 C「我的兑换」要展示评价状态）")
    private Boolean reviewed;

    // ---- 以下仅管理端返回 ----

    @Schema(description = "兑换人 id（仅管理端）")
    private Long volunteerId;

    @Schema(description = "兑换人姓名（仅管理端）")
    private String volunteerName;
}
