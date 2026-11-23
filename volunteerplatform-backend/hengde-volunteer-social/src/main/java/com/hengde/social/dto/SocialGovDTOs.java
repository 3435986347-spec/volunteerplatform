package com.hengde.social.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 社区治理入参（V4 社区治理批）。
 *
 * @author hengde
 */
public final class SocialGovDTOs {

    private SocialGovDTOs() {
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "举报")
    public static class ReportSave {
        @NotNull(message = "请选择举报对象")
        @Schema(description = "1 帖子 / 2 评论")
        private Integer targetType;
        @NotNull(message = "请选择举报对象")
        private Long targetId;
        @NotBlank(message = "请填写举报理由")
        @Size(max = 200, message = "理由不超过 200 字")
        private String reason;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "处理举报")
    public static class ReportHandle {
        @Schema(description = "成立时的处置：0 不处置 / 1 隐藏（仅帖子）/ 2 删除")
        private Integer action;
        @Size(max = 255, message = "说明不超过 255 字")
        private String note;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "驳回帖子")
    public static class Reject {
        @NotBlank(message = "请填写驳回原因")
        @Size(max = 255, message = "原因不超过 255 字")
        private String reason;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "禁言")
    public static class BanSave {
        @NotNull(message = "请选择志愿者")
        private Long volunteerId;
        @NotNull(message = "请选择禁言范围")
        @Schema(description = "2 全部社区写入 / 4 禁止发帖 / 5 禁止评论 / 6 禁止点赞")
        private Integer scope;
        @NotNull(message = "请填写天数")
        private Integer days;
        @NotBlank(message = "请填写原因")
        @Size(max = 255, message = "原因不超过 255 字")
        private String reason;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "提前解除禁言")
    public static class BanLift {
        @NotBlank(message = "请填写解除原因")
        @Size(max = 255, message = "原因不超过 255 字")
        private String reason;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "设置审核员")
    public static class ReviewerSave {
        @NotNull(message = "请选择后台账号")
        private Long adminUserId;
        @NotNull(message = "请选择第几级")
        private Integer level;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "审核设置")
    public static class ReviewSetting {
        @NotNull(message = "请填写审核级数")
        @Schema(description = "帖子要审核几级（1~3）")
        private Integer levels;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "添加关键词")
    public static class KeywordSave {
        @NotBlank(message = "请填写关键词")
        @Size(max = 50, message = "关键词不超过 50 字")
        private String word;
    }
}
