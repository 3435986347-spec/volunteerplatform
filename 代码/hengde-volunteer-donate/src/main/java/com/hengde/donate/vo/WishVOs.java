package com.hengde.donate.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 微心愿的出参（V3 微心愿批）。
 *
 * @author hengde
 */
public final class WishVOs {

    private WishVOs() {
    }

    /** 一个心愿。{@link #masked} 为 true 时姓名 / 学校已打 *（认领前对志愿者）。 */
    @Data
    @Schema(description = "微心愿")
    public static class Wish {
        private Long id;
        private String wishNo;
        private String title;
        private String content;
        private String story;
        private String imageUrl;
        @Schema(description = "受助人姓名（认领前打 *）")
        private String childName;
        private Integer childGender;
        private Integer childAge;
        @Schema(description = "学校（认领前打 *）")
        private String childSchool;
        private String childGrade;
        @Schema(description = "上报单位")
        private String reportOrgName;
        private String remark;
        private Integer status;
        private String statusLabel;
        @Schema(description = "上传时间")
        private LocalDateTime createTime;
        private LocalDateTime realizeTime;
        @Schema(description = "发放反馈图片")
        private List<String> feedbackImages = new ArrayList<>();
        @Schema(description = "资料是否已打 *")
        private boolean masked;
        @Schema(description = "是不是我认领的（认领中或已实现）")
        private boolean claimedByMe;
        @Schema(description = "物资接收地址·收件人（仅认领人可见，Row 12 C「有物资接收地址板块」）")
        private String recvName;
        private String recvPhone;
        private String recvAddress;
        @Schema(description = "当前认领人姓名（仅管理端）")
        private String claimerName;
        @Schema(description = "当前认领时间（仅管理端）")
        private LocalDateTime claimTime;
        @Schema(description = "当前认领记录 id（仅管理端）")
        private Long claimId;
    }

    /** 我的一条认领（Row 35 微心愿中心：认领记录 + 物资明细 + 流转轨迹）。 */
    @Data
    @Schema(description = "我的认领")
    public static class Claim {
        private Long id;
        private Integer status;
        private String statusLabel;
        private LocalDateTime claimTime;
        private LocalDateTime realizeTime;
        private LocalDateTime cancelTime;
        @Schema(description = "撤销原因（后台撤销时）")
        private String cancelReason;
        @Schema(description = "认领人姓名（仅管理端）")
        private String claimerName;
        @Schema(description = "认领人电话（仅管理端，明文——协会要联系他寄物资）")
        private String claimerPhone;
        private Wish wish;
        private List<DonateFlowVOs.Shipment> shipments = new ArrayList<>();
    }

    /** 后台心愿详情：资料全文 + 历次认领（含每次寄来的包裹、物资与轨迹）。 */
    @Data
    @Schema(description = "后台心愿详情")
    public static class AdminDetail {
        private Wish wish;
        @Schema(description = "历次认领，新的在前")
        private List<Claim> claims = new ArrayList<>();
    }

    /** 批量导入结果：<b>全成或全不成</b>，失败时逐行列出原因。 */
    @Data
    @Schema(description = "导入结果")
    public static class ImportResult {
        private int total;
        private int imported;
        private List<String> errors = new ArrayList<>();
    }

    /** 批量下载行（Row 12 F「批量上传、下载」）。含未成年人资料，受 donate:wish 权限保护。 */
    @Data
    @ColumnWidth(16)
    public static class ExportRow {
        @ExcelProperty("心愿编号")
        private String wishNo;
        @ColumnWidth(24)
        @ExcelProperty("标题")
        private String title;
        @ExcelProperty("姓名")
        private String childName;
        @ExcelProperty("性别")
        private String childGender;
        @ExcelProperty("年龄")
        private Integer childAge;
        @ExcelProperty("学校")
        private String childSchool;
        @ExcelProperty("年级")
        private String childGrade;
        @ExcelProperty("上报单位")
        private String reportOrgName;
        @ExcelProperty("状态")
        private String status;
        @ExcelProperty("认领人")
        private String claimerName;
        @ExcelProperty("认领时间")
        private String claimTime;
        @ExcelProperty("实现时间")
        private String realizeTime;
    }
}
