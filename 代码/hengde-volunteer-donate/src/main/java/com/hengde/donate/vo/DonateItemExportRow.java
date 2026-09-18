package com.hengde.donate.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

/**
 * 捐赠物资批量导出行——<b>每个物资一行</b>，列照 Row 17 F 原文的顺序：
 * 目前所在箱子码、专属条形码、物品条形码、捐赠人名字、捐赠人电话、捐赠人单位、志愿者码链接、
 * 物资名称、物资类型、物资数量、快递公司、快递单号、目前进度；另加活动与受赠单位两列便于核对。
 *
 * <p>电话是明文——导出本身就是给线下核对与联系用的，这也是导出单拆一个权限点
 * {@code donate:item-export} 的原因（同 {@code user:export}）。</p>
 *
 * @author hengde
 */
@Data
@ColumnWidth(16)
public class DonateItemExportRow {

    @ExcelProperty("活动")
    private String campaignTitle;

    @ExcelProperty("目前所在箱子码")
    private String boxCode;

    @ExcelProperty("专属条形码")
    private String exclusiveCode;

    @ExcelProperty("物品条形码")
    private String catalogBarcode;

    @ExcelProperty("捐赠人名字")
    private String donorName;

    @ExcelProperty("捐赠人电话")
    private String donorPhone;

    @ExcelProperty("捐赠人单位")
    private String donorOrg;

    @ColumnWidth(40)
    @ExcelProperty("志愿者码链接")
    private String volunteerCodeUrl;

    @ColumnWidth(24)
    @ExcelProperty("物资名称")
    private String name;

    @ExcelProperty("物资类型")
    private String itemType;

    @ExcelProperty("物资数量")
    private Integer quantity;

    @ExcelProperty("快递公司")
    private String expressCompany;

    @ExcelProperty("快递单号")
    private String expressNo;

    @ExcelProperty("目前进度")
    private String progress;

    @ExcelProperty("受赠单位")
    private String recipientOrgName;
}
