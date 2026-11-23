package com.hengde.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 活动时间段/子项目（一个活动可多段；公示名 = 活动名 + 项目名）。
 *
 * <p>时间须落在所属活动的起止区间内（应用层校验）。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("activity_slot")
public class ActivitySlot extends BaseEntity {

    /** 所属活动 activity.id */
    private Long activityId;

    /** 项目名称 */
    private String projectName;

    /** 该时间段开始（精确到分钟） */
    private LocalDateTime startTime;

    /** 该时间段结束 */
    private LocalDateTime endTime;

    /** 需求人数（0=不限） */
    private Integer needCount;

    /**
     * 「活动即将开始」提醒短信的发出时刻；NULL = 尚未提醒（V39）。
     *
     * <p>定时任务据此保证一场只提醒一次——<b>先 CAS 置上再发</b>，宁可漏发也不重发：
     * 短信是有成本的，而且同一条提醒连发两遍会让人以为活动改期了。</p>
     */
    private LocalDateTime reminderSentTime;
}
