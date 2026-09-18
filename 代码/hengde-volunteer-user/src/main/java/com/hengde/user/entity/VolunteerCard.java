package com.hengde.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 志愿者证（V76，Row 26）：一人一张，只存扫码令牌；证件上的信息一律现算。
 *
 * @author hengde
 */
@Data
@TableName("user_volunteer_card")
public class VolunteerCard {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long volunteerId;
    private String token;
    private LocalDateTime issueTime;
    private Integer resetCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
