package com.hengde.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 考勤/积分变更二次审核记录（V14）。
 *
 * <p>组织部申请改志愿者的签到时间/签退时间/积分 → 落一条待审记录，部长审核通过后才应用到
 * {@link ActivityAttendance}。{@code oldValue}/{@code newValue} 统一存字符串，按 {@code changeType} 解释
 * （1/2 为 ISO 时间，3 为整数）。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("activity_attendance_change")
public class ActivityAttendanceChange extends BaseEntity {

    /** 考勤记录 activity_attendance.id */
    private Long attendanceId;

    /** 变更项 1签到时间/2签退时间/3积分 */
    private Integer changeType;

    /** 原值快照（申请时） */
    private String oldValue;

    /** 申请新值 */
    private String newValue;

    /** 变更理由 */
    private String reason;

    /** 0待审/1通过(已应用)/2拒绝 */
    private Integer status;

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
