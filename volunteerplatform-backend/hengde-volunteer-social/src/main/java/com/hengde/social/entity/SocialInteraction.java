package com.hengde.social.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 社区互动（V72）。 */
@Getter
@Setter
@TableName("social_interaction")
public class SocialInteraction {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long recipientId;
    private Long actorId;
    private Integer type;
    private Long postId;
    private Long commentId;
    private Integer isRead;
    private LocalDateTime createTime;
    @TableLogic
    private Integer isDeleted;
    /** 生成列，只读 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String dedupeKey;
}
