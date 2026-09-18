package com.hengde.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 社区主页设置（V71，一人一行；没有行＝全部默认）。
 *
 * @author hengde
 */
@Getter
@Setter
@TableName("social_user_setting")
public class SocialUserSetting {
    @TableId(type = IdType.INPUT)
    private Long volunteerId;
    private Integer forbidFollow;
    private Integer forbidComment;
    private Integer forbidLike;
    /** 1 禁止别人给我发私信（V4 私信批） */
    private Integer forbidChat;
    private String bio;
    private LocalDateTime updateTime;
}
