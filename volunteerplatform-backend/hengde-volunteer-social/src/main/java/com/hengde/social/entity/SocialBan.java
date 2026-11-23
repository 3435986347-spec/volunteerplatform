package com.hengde.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 社区禁言记录（V72；生效与否以 volunteer_sanction 为准）。 */
@Getter
@Setter
@TableName("social_ban")
public class SocialBan {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long volunteerId;
    private Integer scope;
    private Integer days;
    private String reason;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long liftedBy;
    private LocalDateTime liftedTime;
    private String liftReason;
}
