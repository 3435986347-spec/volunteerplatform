package com.hengde.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 社区审核员（V72）。 */
@Getter
@Setter
@TableName("social_reviewer")
public class SocialReviewer {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long adminUserId;
    private Integer level;
    private Long createdBy;
    private LocalDateTime createTime;
}
