package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 活动列表项出参（概要）。
 *
 * @author hengde
 */
@Data
public class ActivityListVO {

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

    /** 活动整体开始时间 */
    private LocalDateTime startTime;

    /** 活动整体结束时间 */
    private LocalDateTime endTime;

    /** 报名截止时间 */
    private LocalDateTime enrollDeadline;

    /** 报名是否需审核 0否/1是 */
    private Integer needAudit;

    /** 状态 0草稿/1已发布/2已结束/3已取消 */
    private Integer status;

    /** 报名人数（活跃报名去重志愿者数：待审核+已通过） */
    private Long enrolledCount;

    /** 是否有名额 1有/0满（任一时间段未满或不限即为有名额；用于推荐排序与「名额已满」标记） */
    private Integer hasQuota;
}
