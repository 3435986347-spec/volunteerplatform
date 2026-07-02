package com.hengde.activity.vo;

import lombok.Data;

import java.math.BigDecimal;
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

    /** 管理团队报名开放时间（内部展示「活动报名时间-管理团队」） */
    private LocalDateTime enrollOpenManager;

    /** 临时负责人报名开放时间（内部展示「活动报名时间-临时负责人」；V1 角色未落地，可能为 null） */
    private LocalDateTime enrollOpenLeader;

    /** 报名范围 0全平台/1指定分队（内部展示「报名限制」；V1 恒为 0=全平台） */
    private Integer enrollScope;

    /** 活动地点纬度（内部展示「定位」+ 前端「到点自动签到」距离预判用） */
    private BigDecimal lat;

    /** 活动地点经度 */
    private BigDecimal lng;

    /** 签到半径（米，默认 500；前端「到点自动签到」用） */
    private Integer checkInRadiusM;

    /** 招募名额总数（各时间段 need_count 之和；0=不限） */
    private Integer needCount;

    /** 已报名人数（活跃报名去重志愿者数：待审核 0 + 已通过 1） */
    private Long enrolledCount;

    /** 报名详情预览：活跃报名者（按报名时间正序、去重志愿者），露完整姓名+报名时间 */
    private List<ActivityRegistrantVO> registrants;

    /** 服务保障项 key 列表（对齐前端 GUARANTEE_ORDER；详情页据此把对应图标渲染为红色，未含则灰） */
    private List<String> serviceGuarantees;

    /** 时间段/子项目 */
    private List<ActivitySlotVO> slots;
}
