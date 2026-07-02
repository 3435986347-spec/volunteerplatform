package com.hengde.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 活动现场负责人（后台预设，可多名，不占报名人数）。
 *
 * <p>{@code leaderType=1} = <b>志愿者负责人</b>（{@code volunteerId} 有值）：本活动报名志愿者，<b>或「管理团队」(manager_flag) 志愿者</b>；
 * 用小程序现场签到/统一签退，参加本活动时积分按 ×1.4。
 * {@code leaderType=2} = <b>后台账号负责人</b>（{@code adminUserId} 有值）：admin_user 子账号；不产生志愿者积分、经后台管理。
 * 历史措辞曾把 2 叫「管理团队」，易与 manager_flag 志愿者混淆——现统一为：从管理团队志愿者安排走 1，2 专指后台 admin_user。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("activity_leader")
public class ActivityLeader extends BaseEntity {

    /** 活动 activity.id */
    private Long activityId;

    /** 负责人来源 1=志愿者负责人(报名志愿者或管理团队 manager_flag 志愿者)/2=后台账号负责人(admin_user) */
    private Integer leaderType;

    /** leaderType=1 时的 volunteer.id（报名志愿者或管理团队志愿者） */
    private Long volunteerId;

    /** leaderType=2 时的 admin_user.id（后台账号负责人） */
    private Long adminUserId;

    /** 指派人（组织部）admin_user.id */
    private Long assignedBy;

    /** 指派时间 */
    private LocalDateTime assignedTime;
}
