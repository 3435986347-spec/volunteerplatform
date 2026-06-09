package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端报名列表出参：一行 = 一条报名记录，含志愿者展示信息与时间段快照。
 *
 * <p>手机号为明文（后台管理需联系志愿者）。</p>
 *
 * @author hengde
 */
@Data
public class EnrollmentAdminVO {

    /** 报名记录 id */
    private Long enrollmentId;

    /** 活动 id（全局报名列表用，按活动列表时与路径 id 一致） */
    private Long activityId;

    /** 活动标题（全局报名列表展示用，按活动列表可为 null） */
    private String activityTitle;

    /** 志愿者 id */
    private Long volunteerId;

    /** 志愿者姓名 */
    private String realName;

    /** 学校 */
    private String school;

    /** 年级 code */
    private Integer grade;

    /** 性别 code 0未知/1男/2女 */
    private Integer gender;

    /** 手机号（明文） */
    private String phone;

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

    /** 审核时间 */
    private LocalDateTime auditTime;

    /** 代报名人 volunteer.id（非代报名为 null） */
    private Long proxyByVolunteerId;

    /** 代报名人姓名（非代报名为 null） */
    private String proxyByName;
}
