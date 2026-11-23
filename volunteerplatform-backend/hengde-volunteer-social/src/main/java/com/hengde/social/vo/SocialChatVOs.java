package com.hengde.social.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 私信出参（V4 私信批）。
 *
 * @author hengde
 */
public final class SocialChatVOs {

    private SocialChatVOs() {
    }

    /** 会话列表的一行（抖音那个私信列表）。 */
    @Data
    public static class Conversation {
        private Long id;
        @Schema(description = "对方（昵称 + 头像；社区不下发真实姓名）")
        private SocialVOs.Author peer;
        private Long peerId;
        private String lastContent;
        @Schema(description = "最后一条是不是我发的")
        private boolean lastFromMe;
        private LocalDateTime lastTime;
        private Integer unread;
    }

    /** 一条消息。 */
    @Data
    public static class Message {
        private Long id;
        private Long senderId;
        @Schema(description = "是不是我发的")
        private boolean mine;
        private String content;
        private String imageUrl;
        private LocalDateTime createTime;
    }

    /** 后台看到的会话（Row 23 F 的聊天记录）。 */
    @Data
    public static class AdminConversation {
        private Long id;
        private Long smallId;
        private Long largeId;
        @Schema(description = "两边的昵称（真实姓名要另外的权限点，见 social:real-name）")
        private String smallName;
        private String largeName;
        private String lastContent;
        private LocalDateTime lastTime;
        private Long messageCount;
    }

    /** 后台看到的一条消息（含双方各自清空的、已被删除的）。 */
    @Data
    public static class AdminMessage {
        private Long id;
        private Long senderId;
        private String senderName;
        private String content;
        private String imageUrl;
        private boolean keywordHit;
        private String keywordHits;
        private boolean deleted;
        private LocalDateTime createTime;
    }

    /** 私聊投诉 / 关键词工单。 */
    @Data
    public static class ChatReport {
        private Long id;
        @Schema(description = "1 用户投诉 / 2 关键词命中（队列里插队在前）")
        private Integer source;
        private String sourceLabel;
        private Long conversationId;
        private Long reporterId;
        private String reporterName;
        private Long targetId;
        private String targetName;
        private Long messageId;
        private String reason;
        private Integer status;
        private String statusLabel;
        private String handleNote;
        private LocalDateTime handledTime;
        private LocalDateTime createTime;
    }
}
