package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 活动补录申请出参（带活动/志愿者上下文）。
 *
 * @author hengde
 */
@Data
public class ActivityBackfillVO {

    /** 补录申请 id */
    private Long id;

    /** 活动 id */
    private Long activityId;

    /** 活动名称 */
    private String activityTitle;

    /** 志愿者 id */
    private Long volunteerId;

    /** 志愿者姓名 */
    private String volunteerName;

    /** 补录时间段 id */
    private Long slotId;

    /** 服务时长（分钟） */
    private Integer serviceMinutes;

    /** 是否发积分 0只记时长/1发积分 */
    private Integer grantPoints;

    /** 匹配方式 idCard/phone */
    private String matchedBy;

    /** 0待审/1通过/2拒绝 */
    private Integer status;

    /** 补录理由 */
    private String reason;

    /** 申请人 admin_user.id */
    private Long requestedBy;

    /** 申请时间 */
    private LocalDateTime requestedTime;

    /** 审核人 admin_user.id */
    private Long auditedBy;

    /** 审核时间 */
    private LocalDateTime auditedTime;

    /** 审核意见/拒绝原因 */
    private String auditReason;
}
