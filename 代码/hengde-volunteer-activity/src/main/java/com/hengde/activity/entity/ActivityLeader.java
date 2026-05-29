package com.hengde.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 活动现场负责人（后台预设，可多名，不占报名人数）。
 *
 * <p>{@code leaderType=1} 时从报名志愿者中选（{@code volunteerId} 有值），该志愿者参加本活动时积分按 ×1.4；
 * {@code leaderType=2} 时从管理团队安排（{@code adminUserId} 有值），不产生志愿者积分但有活动管理权限。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("activity_leader")
public class ActivityLeader extends BaseEntity {

    /** 活动 activity.id */
    private Long activityId;

    /** 负责人来源 1=报名志愿者/2=管理团队 */
    private Integer leaderType;

    /** leaderType=1 时的 volunteer.id */
    private Long volunteerId;

    /** leaderType=2 时的 admin_user.id */
    private Long adminUserId;

    /** 指派人（组织部）admin_user.id */
    private Long assignedBy;

    /** 指派时间 */
    private LocalDateTime assignedTime;
}
