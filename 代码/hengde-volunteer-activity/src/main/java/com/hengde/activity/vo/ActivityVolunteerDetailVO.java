package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 志愿者端活动详情出参（报名所需信息）。
 *
 * <p>不含倍率、发布人、创建时间、指定分队等管理字段：积分倍率是按角色算分的后台配置，
 * 志愿者只需看到积分基数与报名要求/须知。</p>
 *
 * @author hengde
 */
@Data
public class ActivityVolunteerDetailVO {

    /** 活动 id */
    private Long id;

    /** 对外展示编号 */
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

    /** 报名截止时间 */
    private LocalDateTime enrollDeadline;

    /** 取消报名截止 */
    private LocalDateTime cancelDeadline;

    /** 积分基数 */
    private Integer pointsBase;

    /** 报名是否需审核 0否/1是 */
    private Integer needAudit;

    /** 最小年龄要求 */
    private Integer requireMinAge;

    /** 最大年龄要求 */
    private Integer requireMaxAge;

    /** 最低年级要求 */
    private Integer requireMinGrade;

    /** 最高年级要求 */
    private Integer requireMaxGrade;

    /** 性别要求 null不限/1男/2女 */
    private Integer requireGender;

    /** 已参加活动次数门槛 */
    private Integer requireMinJoinCount;

    /** 最少需报名项目数 */
    private Integer minProjects;

    /** 最多可报名项目数 */
    private Integer maxProjects;

    /** 报名须知（弹窗内容） */
    private String enrollNotice;

    /** 须知倒计时秒数 */
    private Integer noticeCountdownSec;

    /** 报名成功提示文字 */
    private String successTipText;

    /** 报名成功提示图片 */
    private String successTipImageUrl;

    /** 状态 0草稿/1已发布/2已结束/3已取消 */
    private Integer status;

    /** 联系人姓名 */
    private String contactName;

    /** 联系人电话 */
    private String contactPhone;

    /** 发布团队/部门名称 */
    private String publisherDeptName;

    /** 志愿者报名开放时间（前端据此渲染「X 月 X 日开放」或「立即报名」） */
    private LocalDateTime enrollOpenVolunteer;

    /** 时间段/子项目 */
    private List<ActivitySlotVO> slots;
}
