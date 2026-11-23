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
    private String activityTitle;
    private Long volunteerId;
    private String volunteerName;

    /**
     * 上下文：所属<b>场次</b>（V30，由考勤行带出）。
     *
     * <p>不带场次时这个审核界面有两处判不了：</p>
     * <ul>
     *   <li>多场次活动里同一人两场都申请改签到，列表上两行的「活动 + 姓名 + 变更项」完全相同；</li>
     *   <li><b>更要紧</b>——部长审的是「签到时间改成 14:00」，
     *       没有岗位时间就无从判断这个新值合不合理：14:00 对下午场正常、对上午 9:00–12:00 那场就是错的。</li>
     * </ul>
     */
    private Long slotId;
    /** 场次（岗位）名称 */
    private String slotProjectName;
    /** 场次开始时间——审核「改签到时间」的合理性参照 */
    private LocalDateTime slotStartTime;
    /** 场次结束时间——审核「改签退时间」的合理性参照 */
    private LocalDateTime slotEndTime;
}
