package com.hengde.social.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 社区入参（V4 社区核心批）。
 *
 * @author hengde
 */
public final class SocialDTOs {

    private SocialDTOs() {
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "发帖 / 修改帖子")
    public static class PostSave {

        @Schema(description = "正文（最多 2000 字；与图片 / 视频至少有一样）")
        private String content;

        @Schema(description = "图片（最多 9 张，先经 POST /v/files/social-image 上传）；与视频二选一")
        private List<String> imageUrls;

        @Schema(description = "视频（1 个，先经 POST /v/files/social-video/presign 直传）；与图片二选一")
        private String videoUrl;

        @Schema(description = "可见性 0 不限制（默认）/ 1 隐藏 / 2 我的关注可看 / 3 关注我的可看")
        private Integer visibility;

        @Schema(description = "允许评论（默认 true）")
        private Boolean allowComment;

        @Schema(description = "允许点赞（默认 true）")
        private Boolean allowLike;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "发布官方帖")
    public static class OfficialPostSave {

        private String content;

        private List<String> imageUrls;

        private String videoUrl;

        @Schema(description = "前端显示的「报名或者分队名称」（可空）")
        @Size(max = 128, message = "名称不超过 128 字")
        private String label;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "评论 / 回复")
    public static class CommentSave {

        @NotBlank(message = "请填写评论内容")
        @Size(max = 500, message = "评论不超过 500 字")
        private String content;

        @Schema(description = "回复哪条评论（不传＝直接评论帖子）")
        private Long parentId;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "主页设置（整份提交）")
    public static class SettingSave {

        @Schema(description = "禁止别人关注我")
        private Boolean forbidFollow;

        @Schema(description = "我的帖子一律禁止评论")
        private Boolean forbidComment;

        @Schema(description = "我的帖子一律禁止点赞")
        private Boolean forbidLike;

        @Schema(description = "禁止别人给我发私信（V4 私信批）")
        private Boolean forbidChat;

        @Schema(description = "主页备注（最多 200 字）")
        @Size(max = 200, message = "备注不超过 200 字")
        private String bio;
    }
}
