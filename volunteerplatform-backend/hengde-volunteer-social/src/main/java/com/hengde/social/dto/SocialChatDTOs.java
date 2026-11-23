package com.hengde.social.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 私信入参（V4 私信批）。
 *
 * @author hengde
 */
public final class SocialChatDTOs {

    private SocialChatDTOs() {
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "发私信（文字与图片至少有一样）")
    public static class Send {

        @Schema(description = "文字（最多 1000 字）")
        @Size(max = 1000, message = "私信不超过 1000 字")
        private String content;

        @Schema(description = "图片（1 张，先经 POST /v/files/social-image 上传）")
        private String imageUrl;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "投诉私聊")
    public static class Report {

        @Schema(description = "投诉理由（最多 200 字）")
        @NotBlank(message = "请填写投诉理由")
        @Size(max = 200, message = "理由不超过 200 字")
        private String reason;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "处理私聊投诉 / 关键词工单")
    public static class Handle {

        @Schema(description = "是否成立")
        @NotNull(message = "请给出结论")
        private Boolean valid;

        @Schema(description = "处理说明（最多 255 字）")
        @Size(max = 255, message = "说明不超过 255 字")
        private String note;

        @Schema(description = "成立时是否连带删除被投诉的那条消息（默认否；禁言另走 POST /a/social/bans）")
        private Boolean deleteMessage;
    }
}
