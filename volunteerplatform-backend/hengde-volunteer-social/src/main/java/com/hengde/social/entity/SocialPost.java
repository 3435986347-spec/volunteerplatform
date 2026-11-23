package com.hengde.social.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 社区帖子（V71，Row 23）。
 *
 * @author hengde
 */
@Getter
@Setter
@TableName("social_post")
public class SocialPost extends BaseEntity {
    private Integer authorType;
    private Long authorId;
    private String officialDepartment;
    private String officialLabel;
    /** 企业帖：发帖时的企业名称快照（social 不依赖 enterprise，渲染时拿不到企业资料） */
    private String authorSnapshotName;
    /** 企业帖：发帖时的企业头像快照 */
    private String authorSnapshotAvatar;
    private String content;
    private Integer mediaType;
    private String mediaUrls;
    private Integer visibility;
    private Integer allowComment;
    private Integer allowLike;
    private Integer reviewStatus;
    /** 已通过几级审核（V73） */
    private Integer reviewLevel;
    /** 1 命中风控关键词：先藏后审（V73） */
    private Integer keywordHit;
    private String keywordHits;
    /** 1 后台隐藏（V73） */
    private Integer adminHidden;
    /** 1 置顶（V73） */
    private Integer pinned;
    private LocalDateTime pinTime;
    private Integer viewCount;
    private Integer likeCount;
    private Integer commentCount;
    private Integer shareCount;
    private LocalDateTime editTime;
    private Integer deletedByType;
    private Long deletedBy;
}
