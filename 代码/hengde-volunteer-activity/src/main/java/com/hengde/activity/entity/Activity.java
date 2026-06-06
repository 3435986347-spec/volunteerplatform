package com.hengde.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 志愿活动（V1 粗粒度，不含签到/时长/公示闭环）。
 *
 * <p>报名限制条件（年龄/年级/性别/已参加次数/最少最多报名项目数）以 null 或 0 表示「不限」。
 * {@code targetSquadIds} 为临时字段，分队模块就绪后改为关联表。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("activity")
public class Activity extends BaseEntity {

    /** 对外展示编号（唯一递增，发布时分配；草稿为空） */
    private Long serialNo;

    /** 活动名称 */
    private String title;

    /** 活动封面图 */
    private String coverImageUrl;

    /** 活动地点 */
    private String location;

    /** 活动内容/介绍 */
    private String content;

    /** 活动要求 */
    private String requirement;

    /** 活动整体开始时间 */
    private LocalDateTime startTime;

    /** 活动整体结束时间 */
    private LocalDateTime endTime;

    /** 报名截止时间（默认活动开始前24h，公示后则为公示时） */
    private LocalDateTime enrollDeadline;

    /** 取消报名截止（此后不可取消；null=随时可取消） */
    private LocalDateTime cancelDeadline;

    /** 积分基数 */
    private Integer pointsBase;

    /** 负责人积分倍率 */
    private BigDecimal leaderMultiplier;

    /** 管理团队积分倍率 */
    private BigDecimal managerMultiplier;

    /** 报名是否需审核 0否(默认报名即通过)/1是 */
    private Integer needAudit;

    /** 报名范围 0全平台/1指定分队 */
    private Integer enrollScope;

    /** 【临时】指定分队id列表（逗号分隔，enrollScope=1时用；分队模块就绪后改关联表） */
    private String targetSquadIds;

    /** 最小年龄要求（null不限） */
    private Integer requireMinAge;

    /** 最大年龄要求（null不限） */
    private Integer requireMaxAge;

    /** 最低年级要求（年级编码，null不限） */
    private Integer requireMinGrade;

    /** 最高年级要求（年级编码，null不限） */
    private Integer requireMaxGrade;

    /** 性别要求 null不限/1男/2女 */
    private Integer requireGender;

    /** 已参加活动次数门槛（默认0=不限） */
    private Integer requireMinJoinCount;

    /** 最少需报名项目数（默认0） */
    private Integer minProjects;

    /** 最多可报名项目数（null=不限） */
    private Integer maxProjects;

    /** 报名须知（弹窗内容） */
    private String enrollNotice;

    /** 须知倒计时秒数（>0则倒计时结束才可确认） */
    private Integer noticeCountdownSec;

    /** 报名成功提示文字（加群引导） */
    private String successTipText;

    /** 报名成功提示图片（群二维码） */
    private String successTipImageUrl;

    /** 状态 0草稿/1已发布/2已结束/3已取消/4待审核发布(小程序提交)/5发布被驳回 */
    private Integer status;

    /** 发布人 admin_user.id */
    private Long createBy;

    /** 联系人姓名（V8） */
    private String contactName;

    /** 联系人电话（V8） */
    private String contactPhone;

    /** 发布团队/部门名称（V8） */
    private String publisherDeptName;

    /** 管理团队报名开放时间（V8，null=即时可报） */
    private LocalDateTime enrollOpenManager;

    /** 临时负责人报名开放时间（V8，V1 未落地角色，字段预留） */
    private LocalDateTime enrollOpenLeader;

    /** 志愿者报名开放时间（V8，EnrollmentService 据此拦截 enroll/proxy） */
    private LocalDateTime enrollOpenVolunteer;

    // ---------- V10 签到/时长/积分闭环 + 现场负责人 ----------

    /** 活动地点纬度（GPS 签到用，null=未设坐标则不可 GPS 签到） */
    private BigDecimal lat;

    /** 活动地点经度（GPS 签到用） */
    private BigDecimal lng;

    /** 签到半径（米，默认 500） */
    private Integer checkInRadiusM;

    /** 现场运行状态 0未开始/1进行中/2已结束（与发布态 status 正交） */
    private Integer runStatus;

    /** 负责人点「活动开始」时间 */
    private LocalDateTime actualStartTime;

    /** 负责人点「活动结束」时间 */
    private LocalDateTime actualEndTime;

    /** 活动总结文字（负责人上传） */
    private String summaryText;

    /** 活动总结图片URL（逗号分隔） */
    private String summaryImages;

    /** 总结上传人（volunteer.id 或 admin_user.id） */
    private Long summaryBy;

    /** 总结上传时间 */
    private LocalDateTime summaryTime;

    /** 已参加活动时长门槛（分钟，默认0=不限；第2批 eligibility 用） */
    private Integer requireMinJoinMinutes;

    /** 0普通活动/1历史补录活动（V16；历史活动补录只记时长不发积分） */
    private Integer isHistorical;

    // ---------- V19 活动发布审核（小程序提交的活动需后台审核） ----------

    /** 发布审核驳回原因（status=5 时；V19） */
    private String publishRejectReason;

    /** 发布审核人 admin_user.id（V19） */
    private Long publishReviewBy;

    /** 发布审核时间（V19） */
    private LocalDateTime publishReviewTime;
}
