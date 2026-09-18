package com.hengde.social.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 社区治理出参（V4 社区治理批）。
 *
 * @author hengde
 */
public final class SocialGovVOs {

    private SocialGovVOs() {
    }

    /** 后台帖子（「按照时间排序显示帖子」那个界面，也是审核队列的行）。 */
    @Data
    public static class AdminPost {
        private Long id;
        private SocialVOs.Author author;
        @Schema(description = "真实姓名：只有持 social:real-name 的账号看得到，其余为空")
        private String realName;
        @Schema(description = "学校：同上")
        private String school;
        private String officialLabel;
        private String content;
        private Integer mediaType;
        private List<String> mediaUrls = new ArrayList<>();
        private Integer visibility;
        private Integer reviewStatus;
        private String reviewStatusLabel;
        @Schema(description = "已通过几级 / 共几级")
        private Integer reviewLevel;
        private Integer reviewLevels;
        private boolean keywordHit;
        private String keywordHits;
        private boolean adminHidden;
        private boolean pinned;
        private Integer viewCount;
        private Integer likeCount;
        private Integer commentCount;
        private Integer shareCount;
        private LocalDateTime createTime;
        private LocalDateTime editTime;
    }

    /** 后台评论（「按照时间排序显示评论」那个界面）。 */
    @Data
    public static class AdminComment {
        private Long id;
        private Long postId;
        private SocialVOs.Author author;
        private String realName;
        private String school;
        private String content;
        private Long parentId;
        private LocalDateTime createTime;
    }

    @Data
    public static class Report {
        private Long id;
        private SocialVOs.Author reporter;
        private Integer targetType;
        private Long targetId;
        private Long postId;
        @Schema(description = "被举报内容的开头（已删除的为空）")
        private String targetSnippet;
        private String reason;
        private Integer status;
        private Integer handleAction;
        private String handleNote;
        private Long handledBy;
        private LocalDateTime handledTime;
        private LocalDateTime createTime;
    }

    @Data
    public static class Ban {
        private Long id;
        private Long volunteerId;
        private SocialVOs.Author volunteer;
        private Integer scope;
        private String scopeLabel;
        private Integer days;
        private String reason;
        private Long createdBy;
        private LocalDateTime createTime;
        private LocalDateTime expireTime;
        @Schema(description = "现在还在生效（没到期、没提前解除）")
        private boolean active;
        private Long liftedBy;
        private LocalDateTime liftedTime;
        private String liftReason;
    }

    @Data
    public static class Reviewer {
        private Long id;
        private Long adminUserId;
        private String adminName;
        private Integer level;
        private LocalDateTime createTime;
    }

    @Data
    public static class Interaction {
        private Long id;
        private Integer type;
        private String typeLabel;
        private SocialVOs.Author actor;
        private Long postId;
        @Schema(description = "帖子开头（帖子已删或看不到时为空）")
        private String postSnippet;
        private Long commentId;
        @Schema(description = "评论内容（评论已删时为空）")
        private String commentContent;
        private boolean read;
        private LocalDateTime createTime;
    }
}
