package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 考勤/积分变更申请展示 VO（管理端列表/审核用）。
 *
 * @author hengde
 */
@Data
public class AttendanceChangeVO {

    private Long id;
    private Long attendanceId;
    /** 变更项 1签到时间/2签退时间/3积分 */
    private Integer changeType;
    private String oldValue;
    private String newValue;
    private String reason;
    /** 0待审/1通过(已应用)/2拒绝 */
    private Integer status;
    private Long requestedBy;
    private LocalDateTime requestedTime;
    private Long auditedBy;
    private LocalDateTime auditedTime;
    private String auditReason;
    /** 上下文：所属活动与志愿者（由考勤行带出） */
    private Long activityId;
    private Long volunteerId;
}
