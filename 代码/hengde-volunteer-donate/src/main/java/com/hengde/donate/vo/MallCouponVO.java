package com.hengde.donate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 卷定义（管理端）。
 *
 * @author hengde
 */
@Data
@Schema(description = "卷定义")
public class MallCouponVO {

    @Schema(description = "卷 id")
    private Long id;

    @Schema(description = "卷名称")
    private String name;

    @Schema(description = "类型 1指定商品兑换卷/2积分满减卷")
    private Integer type;

    @Schema(description = "类型中文名")
    private String typeLabel;

    @Schema(description = "适用商品 id；满减卷为空=全场通用")
    private Long goodsId;

    @Schema(description = "适用商品名（当前名，非快照）")
    private String goodsName;

    @Schema(description = "满减门槛")
    private Integer thresholdPoints;

    @Schema(description = "满减抵扣积分；兑换卷为空（全额抵扣）")
    private Integer discountPoints;

    @Schema(description = "有效期起（含）")
    private LocalDateTime validStart;

    @Schema(description = "有效期止（不含）")
    private LocalDateTime validEnd;

    @Schema(description = "1启用/0停用")
    private Integer status;

    @Schema(description = "使用说明")
    private String description;

    @Schema(description = "已发放张数（含已作废）")
    private Long grantedCount;

    @Schema(description = "已使用张数")
    private Long usedCount;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
