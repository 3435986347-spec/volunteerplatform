package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 活动现场负责人展示 VO。
 *
 * @author hengde
 */
@Data
public class ActivityLeaderVO {

    private Long id;
    private Long activityId;
    /** 1=报名志愿者/2=管理团队 */
    private Integer leaderType;
    private Long volunteerId;
    /** 志愿者负责人姓名（leaderType=1 时有值） */
    private String volunteerName;
    private Long adminUserId;
    private LocalDateTime assignedTime;
}
