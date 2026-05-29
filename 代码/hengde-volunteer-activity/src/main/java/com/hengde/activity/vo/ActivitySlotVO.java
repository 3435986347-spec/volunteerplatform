package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 活动时间段/子项目出参。
 *
 * @author hengde
 */
@Data
public class ActivitySlotVO {

    /** 时间段 id */
    private Long id;

    /** 项目名称 */
    private String projectName;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 需求人数（0=不限） */
    private Integer needCount;
}
