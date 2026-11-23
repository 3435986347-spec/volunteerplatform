package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 活动留言出参（含发表人姓名）。
 *
 * @author hengde
 */
@Data
public class ActivityMessageVO {

    /** 留言 id */
    private Long id;

    /** 活动 id */
    private Long activityId;

    /** 发表人 volunteer.id */
    private Long volunteerId;

    /** 发表人姓名 */
    private String volunteerName;

    /** 留言内容 */
    private String content;

    /** 发表时间 */
    private LocalDateTime createTime;
}
