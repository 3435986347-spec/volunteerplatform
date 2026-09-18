package com.hengde.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 社区举报（V72）。 */
@Getter
@Setter
@TableName("social_report")
public class SocialReport {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long reporterId;
    private Integer targetType;
    private Long targetId;
    private Long postId;
    private String reason;
    private Integer status;
    private Integer handleAction;
    private String handleNote;
    private Long handledBy;
    private LocalDateTime handledTime;
    private LocalDateTime createTime;
    /** 生成列，只读 */
    @TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String activeKey;
}
