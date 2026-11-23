package com.hengde.social.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 私信消息（V82）。后台永久保存——志愿者「清空聊天记录」只是水位，行一条都不删（Row 23 F）。
 *
 * @author hengde
 */
@Getter
@Setter
@TableName("social_message")
public class SocialMessage {
    private Long id;
    private Long conversationId;
    private Long senderId;
    private Long receiverId;
    private String content;
    private String imageUrl;
    private Integer keywordHit;
    private String keywordHits;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer isDeleted;
}
