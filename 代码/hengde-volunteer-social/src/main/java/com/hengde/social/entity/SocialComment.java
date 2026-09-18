package com.hengde.social.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 帖子评论（V71）。
 *
 * @author hengde
 */
@Getter
@Setter
@TableName("social_comment")
public class SocialComment extends BaseEntity {
    private Long postId;
    private Integer authorType;
    private Long authorId;
    private String authorDepartment;
    private Long parentId;
    private Integer replyToAuthorType;
    private Long replyToAuthorId;
    private String content;
    private Integer deletedByType;
    private Long deletedBy;
}
