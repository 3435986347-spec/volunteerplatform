package com.hengde.activity.album.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 活动相册入参（V4 活动相册批）。
 *
 * @author hengde
 */
public final class AlbumDTOs {

    private AlbumDTOs() {
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "上传一批照片")
    public static class Upload {
        @NotEmpty(message = "请至少选一张照片")
        @Schema(description = "照片（先经 POST /v/files/album-photo 或 /a/files/upload?dir=album 上传的原图；一次最多 50 张）")
        private List<String> photoUrls;
        @Size(max = 500, message = "评论不超过 500 字")
        private String comment;
        @Schema(description = "同时发送到交流平台（默认 true；后台上传忽略）")
        private Boolean syncSocial;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "后台新增相册")
    public static class Create {
        @Schema(description = "挂到哪个活动（给了就是那个活动的相册，已有就直接返回那一个）")
        private Long activityId;
        @Size(max = 160, message = "标题不超过 160 字")
        @Schema(description = "标题（不挂活动时必填）")
        private String title;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "驳回相册积分")
    public static class Reject {
        @NotBlank(message = "请填写驳回原因")
        @Size(max = 255, message = "原因不超过 255 字")
        private String reason;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "上传积分规则")
    public static class Rule {
        @NotNull(message = "请选择是否给积分")
        private Boolean enabled;
        @NotNull(message = "请填写每多少张")
        private Integer photosPerUnit;
        @NotNull(message = "请填写给多少分")
        private Integer pointsPerUnit;
        @NotNull(message = "请填写每人每相册上限")
        private Integer maxPointsPerAlbum;
    }
}
