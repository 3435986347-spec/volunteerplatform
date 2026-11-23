package com.hengde.donate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 物资搜索行（{@code DonateItemMapper.search} 的结果）：物资本身 + 所属运单 / 箱子 / 活动的几列。
 *
 * @author hengde
 */
@Data
@Schema(description = "物资搜索行")
public class DonateItemRow {

    private Long id;
    private Long shipmentId;
    private Integer bizType;
    private Long bizId;
    private Long donorVolunteerId;
    private String name;
    private Integer itemType;
    @Schema(description = "物资类型中文名")
    private String itemTypeLabel;
    private Integer quantity;
    private String catalogBarcode;
    private String exclusiveCode;
    private Integer status;
    @Schema(description = "目前进度")
    private String statusLabel;
    private String checkRemark;
    private Long boxId;
    private String boxCode;
    private Long recipientOrgId;
    private String recipientOrgName;
    private LocalDateTime deliverTime;
    private Integer borrowCount;
    private LocalDateTime createTime;
    private String donorName;
    private String donorOrg;
    private String expressCompany;
    private String expressNo;
    private String campaignTitle;
}
