package com.hengde.social.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 社区出参（V4 社区核心批）。
 *
 * <p><b>志愿者只露昵称与头像，不露真实姓名与学校</b>（Row 23 F「最高权限管理员才可以看到发布人的真实姓名和学校」，
 * 那是治理批的后台界面）；官方帖 / 官方评论露部门。</p>
 *
 * @author hengde
 */
public final class SocialVOs {

    private SocialVOs() {
    }

    /** 发帖 / 评论的人。 */
    @Data
    public static class Author {
        @Schema(description = "1 志愿者 / 2 官方")
        private Integer type;
        @Schema(description = "志愿者 id（官方为空）")
        private Long volunteerId;
        @Schema(description = "展示名：志愿者取昵称（没设昵称为「志愿者」+编号尾号），官方为「官方 · 部门」")
        private String name;
        private String avatarUrl;
    }

    @Data
    public static class Post {
        private Long id;
        private Author author;
        @Schema(description = "官方帖：报名或分队名称")
        private String officialLabel;
        private String content;
        @Schema(description = "0 纯文字 / 1 图片 / 2 视频")
        private Integer mediaType;
        private List<String> mediaUrls = new ArrayList<>();
        private Integer visibility;
        private String visibilityLabel;
        @Schema(description = "帖子本身允许评论，且作者没有在主页禁止评论")
        private boolean commentable;
        @Schema(description = "帖子本身允许点赞，且作者没有在主页禁止点赞")
        private boolean likeable;
        private Integer viewCount;
        private Integer likeCount;
        private Integer commentCount;
        private Integer shareCount;
        private boolean likedByMe;
        @Schema(description = "是不是我发的（前端据此显示修改 / 删除）")
        private boolean mine;
        private LocalDateTime createTime;
        private LocalDateTime editTime;
        @Schema(description = "后台置顶")
        private boolean pinned;
        @Schema(description = "审核状态（只有自己的帖子才有：审核中 / 已通过 / 未通过）")
        private String reviewStatusLabel;
        @Schema(description = "被后台隐藏了（只有自己的帖子才有）")
        private Boolean hiddenByAdmin;
        @Schema(description = "命中了风控关键词、审核通过前别人看不到（只有自己的帖子才有）")
        private Boolean awaitingKeywordReview;
    }

    @Data
    public static class Comment {
        private Long id;
        private Long postId;
        private Author author;
        private Long parentId;
        @Schema(description = "回复的是谁（直接评论帖子时为空）")
        private Author replyTo;
        private String content;
        @Schema(description = "我能不能删（评论是我的，或帖子是我的）")
        private boolean deletable;
        private LocalDateTime createTime;
    }

    @Data
    public static class Medal {
        private Long medalId;
        private String name;
        private String iconUrl;
    }

    @Data
    public static class Profile {
        private Long volunteerId;
        private String name;
        private String avatarUrl;
        @Schema(description = "注册（实名）时间；游客为空")
        private LocalDateTime registerTime;
        private List<Medal> medals = new ArrayList<>();
        private String bio;
        @Schema(description = "发帖量（我看得到的）")
        private long postCount;
        private long followerCount;
        private long followingCount;
        @Schema(description = "收到的点赞量")
        private long likeCount;
        private boolean self;
        @Schema(description = "我关注了 TA")
        private boolean followedByMe;
        @Schema(description = "TA 关注了我")
        private boolean followsMe;
        @Schema(description = "TA 设置了禁止关注")
        private boolean followForbidden;
        @Schema(description = "我对 TA 设置了「不让TA看」（仅看自己以外的人时有意义）")
        private boolean blockedByMe;
    }

    /** 关注 / 粉丝 / 不让TA看 列表里的一个人。 */
    @Data
    public static class UserCard {
        private Long volunteerId;
        private String name;
        private String avatarUrl;
        private boolean followedByMe;
    }

    @Data
    public static class OfficialPost {
        private Long id;
        private String department;
        private String label;
        private String content;
        private Integer mediaType;
        private List<String> mediaUrls = new ArrayList<>();
        private Integer viewCount;
        private Integer likeCount;
        private Integer commentCount;
        private Integer shareCount;
        private Long authorAdminId;
        private String authorAdminName;
        private LocalDateTime createTime;
    }

    @Data
    public static class Setting {
        private boolean forbidFollow;
        private boolean forbidComment;
        private boolean forbidLike;
        @Schema(description = "禁止别人给我发私信（V4 私信批）")
        private boolean forbidChat;
        private String bio;
    }
}
