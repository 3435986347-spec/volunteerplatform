package com.hengde.donate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 赞助企业看到的兑换单（V4 爱心企业批·商品段）。
 *
 * <p><b>刻意比后台的 {@link MallOrderVO} 少</b>：没有取货码（柜台上的持有者凭据）、没有收件人电话地址、兑换人姓名只留姓——
 * 企业只需要知道自己的商品被谁（大致）换了几件、到没到手。字段集合由用例反射钉住。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "赞助企业的兑换单")
public class SponsorOrderVO {
    private Long id;
    private String orderNo;
    private Long goodsId;
    private String goodsName;
    private String specName;
    @Schema(description = "商品部分实际扣的积分（不含抵扣快递费的部分）")
    private Integer goodsPoints;
    private Integer deliveryType;
    private String deliveryTypeLabel;
    private Integer status;
    private String statusLabel;
    @Schema(description = "兑换人（只留姓）")
    private String volunteerMaskedName;
    private LocalDateTime createTime;
    private LocalDateTime pickupTime;
}
