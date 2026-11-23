package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 「我负责的活动」场次列表行。
 *
 * @author hengde
 */
@Data
public class ManagedActivityVO {

    private Long activityId;
    private Long serialNo;
    private String title;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 现场运行状态 0未开始/1进行中/2已结束 */
    private Integer runStatus;
    /** 参加（活跃报名）志愿者人数 */
    private Long enrolledCount;
}
