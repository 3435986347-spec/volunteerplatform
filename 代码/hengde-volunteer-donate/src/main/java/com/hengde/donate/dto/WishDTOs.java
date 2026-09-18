package com.hengde.donate.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 微心愿的入参（V3 微心愿批）。几个小类放在一个文件里，理由同 {@code BoxDTOs}。
 *
 * @author hengde
 */
public final class WishDTOs {

    private WishDTOs() {
    }

    /** 后台单独上传 / 修改一个心愿（Row 12 F「单独上传」）。 */
    @Data
    @Schema(description = "微心愿")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Save {

        @Schema(description = "心愿编号；不填由系统生成")
        @Size(max = 32, message = "心愿编号过长")
        private String wishNo;

        @Schema(description = "标题（心愿）")
        @NotBlank(message = "请填写心愿标题")
        @Size(max = 128, message = "标题不超过 128 字")
        private String title;

        @Schema(description = "心愿内容")
        @Size(max = 512, message = "心愿内容不超过 512 字")
        private String content;

        @Schema(description = "心愿故事")
        private String story;

        @Schema(description = "图片 URL")
        @Size(max = 512, message = "图片 URL 过长")
        private String imageUrl;

        @Schema(description = "受助人姓名（密文存储；认领前对志愿者打 *）")
        @NotBlank(message = "请填写受助人姓名")
        @Size(max = 32, message = "姓名过长")
        private String childName;

        @Schema(description = "性别 1男/2女")
        private Integer childGender;

        @Schema(description = "年龄")
        @Min(value = 1, message = "年龄不正确")
        @Max(value = 30, message = "年龄不正确")
        private Integer childAge;

        @Schema(description = "学校（密文存储；认领前打 *）")
        @Size(max = 64, message = "学校不超过 64 字")
        private String childSchool;

        @Schema(description = "年级")
        @Size(max = 32, message = "年级过长")
        private String childGrade;

        @Schema(description = "上报单位 id（受赠单位主数据）")
        private Long reportOrgId;

        @Schema(description = "备注")
        @Size(max = 512, message = "备注过长")
        private String remark;
    }

    /**
     * 批量导入的一行（Row 12 G「后台导入微心愿资料」）。列名就是模板表头；上报单位按<b>名称</b>对上主数据。
     */
    @Data
    public static class ImportRow {

        @ExcelProperty("心愿编号")
        private String wishNo;

        @ExcelProperty("标题")
        private String title;

        @ExcelProperty("心愿内容")
        private String content;

        @ExcelProperty("心愿故事")
        private String story;

        @ExcelProperty("图片")
        private String imageUrl;

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

        @ExcelProperty("备注")
        private String remark;
    }

    /** 实现心愿：物资发放反馈图片（Row 12 G「给捐赠人反馈物资发放图片」）。 */
    @Data
    @Schema(description = "实现心愿")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Realize {

        @Schema(description = "发放反馈图片 URL（1~9 张）")
        @Size(min = 1, max = 9, message = "请上传 1~9 张发放图片")
        private List<String> images;
    }

    /** 后台撤销认领（Row 12 F「取消认领」）。原因必填——认领人会看到。 */
    @Data
    @Schema(description = "撤销认领")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Revoke {

        @Schema(description = "撤销原因")
        @NotBlank(message = "请填写撤销原因")
        @Size(max = 512, message = "撤销原因不超过 512 字")
        private String reason;
    }
}
