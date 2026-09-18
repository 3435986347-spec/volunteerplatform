package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    /**
     * 要用的卷（「我的卷」里那条发放记录的 id）。<b>一单至多一张，不叠加</b>（清单 ④ 默认）。
     *
     * <p>商品要求指定卷时必填；可用的卷可先查 {@code GET /v/donate/coupons/usable?specId=}。</p>
     */
    @Schema(description = "要用的卷发放记录 id；商品要求指定卷时必填")
    private Long couponGrantId;

    // ---- 商城快递批 ----

    @Schema(description = "领取方式 1自提（默认）/2快递")
    private Integer deliveryType;

    /** 快递时必填：1 现金支付快递费 / 2 积分抵扣。 */
    @Schema(description = "快递费支付方式 1现金/2积分抵扣（快递时必填）")
    private Integer shippingPayType;

    @Schema(description = "收件人（快递时必填）")
    @Size(max = 64, message = "收件人过长")
    private String recvName;

    @Schema(description = "收件电话（快递时必填，11 位手机号）")
    @Pattern(regexp = "^$|1\\d{10}", message = "请填写 11 位手机号")
    private String recvPhone;

    @Schema(description = "收件地址（快递时必填）")
    @Size(max = 255, message = "收件地址过长")
    private String recvAddress;
}
