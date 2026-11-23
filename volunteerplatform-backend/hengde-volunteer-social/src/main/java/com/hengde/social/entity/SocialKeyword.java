package com.hengde.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 社区风控关键词（V72）。 */
@Getter
@Setter
@TableName("social_keyword")
public class SocialKeyword {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String word;
    private Long createdBy;
    private LocalDateTime createTime;
}
