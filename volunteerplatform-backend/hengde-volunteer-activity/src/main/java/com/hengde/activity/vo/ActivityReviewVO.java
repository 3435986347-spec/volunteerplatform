package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待审核发布活动出参（{@code GET /a/activity/activities/pending-reviews}）。
 *
 * <p>列出小程序提交、等后台审核上线的活动，带提交人姓名供审核者识别。完整字段经
 * {@code GET /a/activity/activities/{id}} 详情查看。</p>
 *
 * @author hengde
 */
@Data
public class ActivityReviewVO {

    private Long id;
    private Long serialNo;
    private String title;
    private String location;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    /** 提交人 volunteer.id（管理团队志愿者） */
    private Long submitterId;
    /** 提交人姓名 */
    private String submitterName;

    /** 提交时间（活动创建时间） */
    private LocalDateTime submitTime;
}
