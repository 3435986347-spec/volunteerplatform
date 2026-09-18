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

    @Schema(description = "实际扣除积分（下单时快照；用卷后可能低于标价，兑换卷为 0）")
    private Integer points;

    @Schema(description = "规格标价积分（下单时快照）")
    private Integer originalPoints;

    @Schema(description = "所用卷名称（下单时快照；未用卷为空）")
    private String couponName;

    @Schema(description = "卷抵扣积分（下单时快照）")
    private Integer couponDeductPoints;

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

    // ---- 商城快递批：现金与快递 ----

    @Schema(description = "领取方式中文名")
    private String deliveryTypeLabel;

    @Schema(description = "商品现金部分【分】")
    private Integer goodsCashFen;

    @Schema(description = "快递费【分】（下单时快照）")
    private Integer shippingFeeFen;

    @Schema(description = "快递费支付方式 1现金/2积分抵扣")
    private Integer shippingPayType;

    private String shippingPayTypeLabel;

    @Schema(description = "快递费折成的积分（已计入 points）")
    private Integer shippingPoints;

    @Schema(description = "积分抵扣汇率快照：1 元折多少积分")
    private Integer pointsPerYuan;

    @Schema(description = "应付现金【分】")
    private Integer payCashFen;

    @Schema(description = "应付现金【元】，展示用——前端不要自己除以 100")
    private String payCashYuan;

    @Schema(description = "付款截止（仅待支付）")
    private LocalDateTime payExpireTime;

    @Schema(description = "付款成功时间")
    private LocalDateTime paidTime;

    @Schema(description = "收件人（本人与后台可见）")
    private String recvName;

    @Schema(description = "收件电话，明文（本人与后台可见）")
    private String recvPhone;

    @Schema(description = "收件地址（本人与后台可见）")
    private String recvAddress;

    @Schema(description = "快递公司")
    private String expressCompany;

    @Schema(description = "快递单号")
    private String expressNo;

    @Schema(description = "发货时间")
    private LocalDateTime shipTime;

    // ---- 以下仅管理端返回 ----

    @Schema(description = "兑换人 id（仅管理端）")
    private Long volunteerId;

    @Schema(description = "兑换人姓名（仅管理端）")
    private String volunteerName;

    /**
     * 核销人类型：1 管理员 / 2 核销员（志愿者）；未核销为空。
     * 核销人 id 一列同时装两类账号的 id，不带类型就分不清是谁（跨域同号错认那一类问题）。
     */
    @Schema(description = "核销人类型 1管理员/2核销员/3本人确认收货/4系统自动确认收货（仅管理端；未核销为空）")
    private Integer pickupOperatorType;

    @Schema(description = "付款成功的交易单 id（仅管理端；到收付页查流水、重试退款用）")
    private Long tradeOrderId;

    @Schema(description = "驳回后发起的现金退款单号（仅管理端）")
    private String cashRefundNo;

    @Schema(description = "现金退款发起失败的原因（仅管理端；非空说明钱还没退出去，要到收付页重试）")
    private String cashRefundError;
}
