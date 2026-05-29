package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 「我的报名」列表出参：一行 = 我在某活动某时间段的一条报名记录（含活动与时间段快照）。
 *
 * @author hengde
 */
@Data
public class MyEnrollmentVO {

    /** 报名记录 id */
    private Long enrollmentId;

    /** 活动 id */
    private Long activityId;

    /** 活动对外编号 */
    private Long serialNo;

    /** 活动名称 */
    private String activityTitle;

    /** 时间段 id */
    private Long slotId;

    /** 时间段/项目名称 */
    private String projectName;

    /** 时间段开始 */
    private LocalDateTime slotStartTime;

    /** 时间段结束 */
    private LocalDateTime slotEndTime;

    /** 报名状态 0待审核/1已通过/2已拒绝/3已取消 */
    private Integer status;

    /** 报名时间 */
    private LocalDateTime enrollTime;

    /** 拒绝原因（status=2 时有值） */
    private String rejectReason;
}
