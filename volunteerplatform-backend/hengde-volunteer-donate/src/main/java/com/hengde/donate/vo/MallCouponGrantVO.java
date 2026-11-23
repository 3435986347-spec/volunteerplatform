package com.hengde.donate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一张已发出的卷（「我的卷」与管理端发放记录共用）。
 *
 * <p>条款一律取<b>发放时的快照</b>，不取卷定义的当前值。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "已发出的卷")
public class MallCouponGrantVO {

    @Schema(description = "发放记录 id（下单用卷时传这个）")
    private Long id;

    @Schema(description = "卷定义 id")
    private Long couponId;

    @Schema(description = "卷名称（发放时快照）")
    private String couponName;

    @Schema(description = "类型 1指定商品兑换卷/2积分满减卷")
    private Integer type;

    @Schema(description = "类型中文名")
    private String typeLabel;

    @Schema(description = "适用商品 id；为空=全场通用")
    private Long goodsId;

    @Schema(description = "满减门槛")
    private Integer thresholdPoints;

    @Schema(description = "抵扣积分；兑换卷为空（全额抵扣）")
    private Integer discountPoints;

    @Schema(description = "有效期起（含）")
    private LocalDateTime validStart;

    @Schema(description = "到期时刻（不含）")
    private LocalDateTime expireTime;

    @Schema(description = "状态 0未使用/1已使用/2已作废/3已过期（3 为按时间现算的派生态）")
    private Integer status;

    @Schema(description = "状态中文名")
    private String statusLabel;

    @Schema(description = "用在哪张兑换单上")
    private Long usedOrderId;

    @Schema(description = "使用时间")
    private LocalDateTime usedTime;

    @Schema(description = "发放时间")
    private LocalDateTime createTime;

    // ---- 以下仅管理端返回 ----

    @Schema(description = "持有人 id（仅管理端）")
    private Long volunteerId;

    @Schema(description = "持有人姓名（仅管理端）")
    private String volunteerName;

    @Schema(description = "作废原因（仅管理端）")
    private String revokeReason;

    @Schema(description = "作废时间（仅管理端）")
    private LocalDateTime revokeTime;
}
