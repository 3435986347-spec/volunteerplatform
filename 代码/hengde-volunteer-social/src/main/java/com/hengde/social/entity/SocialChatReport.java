package com.hengde.social.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 私聊投诉与关键词工单（V82，Row 23 F）。
 *
 * <p>两种来源合在一张表：用户投诉（{@link #SOURCE_USER}）与关键词命中自动生成（{@link #SOURCE_KEYWORD}，队列里插队在前）。
 * 分两张表的话，审核员要在两个队列之间来回切，而他要做的是同一件事：看这段对话、决定处不处置。</p>
 *
 * @author hengde
 */
@Getter
@Setter
@TableName("social_chat_report")
public class SocialChatReport {

    /** 用户投诉 */
    public static final int SOURCE_USER = 1;
    /** 关键词命中自动生成（插队） */
    public static final int SOURCE_KEYWORD = 2;

    public static final int PENDING = 0;
    public static final int VALID = 1;
    public static final int INVALID = 2;

    private Long id;
    private Integer source;
    private Long reporterId;
    private Long targetId;
    private Long conversationId;
    private Long messageId;
    private String reason;
    private Integer status;
    private String handleNote;
    private Long handledBy;
    private LocalDateTime handledTime;
    private LocalDateTime createTime;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String activeKey;
}
