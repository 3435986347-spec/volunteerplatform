package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 结对与众筹的入参（V3 结对批）。几个小类放在一个文件里，理由同 {@code WishDTOs}。
 *
 * @author hengde
 */
public final class PairDTOs {

    private PairDTOs() {
    }

    /** 新建 / 修改结对项目。 */
    @Data
    @Schema(description = "结对项目")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProjectSave {

        @Schema(description = "项目名称")
        @NotBlank(message = "请填写项目名称")
        @Size(max = 128, message = "项目名称不超过 128 字")
        private String title;

        @Schema(description = "类型 1结对助学/2结对助困/3结对助残")
        private Integer projectType;

        @Schema(description = "封面图")
        @Size(max = 512, message = "封面图地址过长")
        private String coverUrl;

        @Schema(description = "项目详情（图文）")
        private String detail;

        @Schema(description = "受助金额（目标）")
        @NotNull(message = "请填写受助金额")
        @DecimalMin(value = "0.01", message = "受助金额须大于 0")
        @Digits(integer = 10, fraction = 2, message = "受助金额格式不正确")
        private BigDecimal targetAmount;
    }

    /** 结对登记（志愿者端）。<b>本批不含支付</b>：登记的是「我认捐多少」。 */
    @Data
    @Schema(description = "结对登记")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Register {

        @Schema(description = "认捐方式 1指定金额/2全款；全款时金额由服务端按项目缺口计算")
        private Integer amountType;

        @Schema(description = "认捐金额（指定金额时必填）")
        @DecimalMin(value = "0.01", message = "认捐金额须大于 0")
        @Digits(integer = 10, fraction = 2, message = "认捐金额格式不正确")
        private BigDecimal amount;

        @Schema(description = "留言")
        @Size(max = 512, message = "留言不超过 512 字")
        private String remark;
    }

    /** 取消结对登记（后台取消时原因必填——结对人会看到）。 */
    @Data
    @Schema(description = "取消结对")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Cancel {

        @Schema(description = "取消原因")
        @NotBlank(message = "请填写取消原因")
        @Size(max = 512, message = "取消原因不超过 512 字")
        private String reason;
    }

    /** 录入受助方来信（Row 10 图文展示板块）。受助方不直接使用系统，由协会代录。 */
    @Data
    @Schema(description = "受助方来信")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LetterSave {

        @Schema(description = "收信的结对登记 id；不填=项目公开信（所有人可见）")
        private Long pairRecordId;

        @Schema(description = "标题")
        @Size(max = 128, message = "标题不超过 128 字")
        private String title;

        @Schema(description = "正文")
        @NotBlank(message = "请填写来信内容")
        private String content;

        @Schema(description = "图片 URL，最多 9 张")
        @Size(max = 9, message = "图片最多 9 张")
        private List<String> images;

        @Schema(description = "来信时间（受助方写信那天；不填取录入时间）")
        private LocalDateTime writeTime;
    }

    /** 新建 / 修改众筹项目。 */
    @Data
    @Schema(description = "众筹项目")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CrowdfundSave {

        @Schema(description = "项目名称")
        @NotBlank(message = "请填写项目名称")
        @Size(max = 128, message = "项目名称不超过 128 字")
        private String title;

        @Schema(description = "封面图")
        @Size(max = 512, message = "封面图地址过长")
        private String coverUrl;

        @Schema(description = "项目详情（图文）")
        private String detail;

        @Schema(description = "预计金额（目标）")
        @NotNull(message = "请填写预计金额")
        @DecimalMin(value = "0.01", message = "预计金额须大于 0")
        @Digits(integer = 10, fraction = 2, message = "预计金额格式不正确")
        private BigDecimal targetAmount;

        @Schema(description = "开始时间")
        private LocalDateTime startTime;

        @Schema(description = "结束时间")
        private LocalDateTime endTime;

        @Schema(description = "是否接受捐款（不传＝接受）")
        private Boolean acceptMoney;

        @Schema(description = "是否接受捐物（不传＝不接受）")
        private Boolean acceptGoods;

        @Schema(description = "需要哪些物资（给捐物的人看）")
        @Size(max = 512, message = "物资需求不超过 512 字")
        private String goodsNeeded;

        @Schema(description = "物资收件人（接受捐物时必填）")
        @Size(max = 64, message = "收件人过长")
        private String recvName;

        @Schema(description = "物资收件电话（接受捐物时必填）")
        @Size(max = 32, message = "收件电话过长")
        private String recvPhone;

        @Schema(description = "物资收件地址（接受捐物时必填）")
        @Size(max = 255, message = "收件地址过长")
        private String recvAddress;
    }
}
