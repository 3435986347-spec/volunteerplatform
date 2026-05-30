package com.hengde.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 活动补录申请与审核（V16，第 3 批·PR3）。
 *
 * <p>组织部按手机号/身份证精确匹配志愿者 + 指定时间段申请补录 → 待部长审核。通过即终态：直接落一条
 * 已确认考勤行；{@code grantPoints} 申请时据 {@link Activity#getIsHistorical()} 快照（普通活动 1=发积分、
 * 历史活动 0=只记时长）。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("activity_backfill")
public class ActivityBackfill extends BaseEntity {

    /** 活动 activity.id */
    private Long activityId;

    /** 补录目标 volunteer.id */
    private Long volunteerId;

    /** 补录时间段 activity_slot.id */
    private Long slotId;

    /** 服务时长快照（按 slot 时长，分钟） */
    private Integer serviceMinutes;

    /** 是否发积分：申请时据 activity.is_historical 快照（普通1/历史0） */
    private Integer grantPoints;

    /** 匹配方式 idCard/phone（审计展示） */
    private String matchedBy;

    /** 0待审/1通过(已生效)/2拒绝 */
    private Integer status;

    /** 补录理由 */
    private String reason;

    /** 申请人（组织部）admin_user.id */
    private Long requestedBy;

    /** 申请时间 */
    private LocalDateTime requestedTime;

    /** 审核人（部长）admin_user.id */
    private Long auditedBy;

    /** 审核时间 */
    private LocalDateTime auditedTime;

    /** 审核意见/拒绝原因 */
    private String auditReason;
}
