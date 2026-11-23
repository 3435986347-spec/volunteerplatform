package com.hengde.donate.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 物资 10 维搜索（Row 17 F：箱子码、专属条形码、物品条形码、捐赠人名字、捐赠人电话、捐赠人单位、
 * 物资名称、物资类型、快递单号、目前进度），另加来源与受赠单位筛选。分页参数走 {@code PageQuery}。
 *
 * <p>{@link #donorPhone} 不进 SQL：手机号是密文，服务层先经 auth 换成 {@link #donorVolunteerId}。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "捐赠物资搜索条件")
public class DonateItemQuery {

    @Schema(description = "来源类型 1捐书活动")
    private Integer bizType;

    @Schema(description = "来源 id（捐书活动 id）")
    private Long bizId;

    @Schema(description = "箱子码（精确）")
    private String boxCode;

    @Schema(description = "物品专属码（精确）")
    private String exclusiveCode;

    @Schema(description = "物品条形码 / ISBN（精确）")
    private String catalogBarcode;

    @Schema(description = "捐赠人名字（模糊，按登记时快照）")
    private String donorName;

    @Schema(description = "捐赠人电话（精确）")
    private String donorPhone;

    @Schema(hidden = true)
    private Long donorVolunteerId;

    @Schema(description = "捐赠人单位（模糊）")
    private String donorOrg;

    @Schema(description = "物资名称（模糊）")
    private String itemName;

    @Schema(description = "物资类型 1课外书籍/2学习用品/3运动器材/9其他")
    private Integer itemType;

    @Schema(description = "快递单号（精确）")
    private String expressNo;

    @Schema(description = "目前进度（物资状态码）")
    private Integer status;

    @Schema(description = "受赠单位 id")
    private Long recipientOrgId;
}
