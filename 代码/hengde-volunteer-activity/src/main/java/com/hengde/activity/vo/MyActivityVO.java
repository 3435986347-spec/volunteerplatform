package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 志愿者「我的活动」列表行：我已通过报名的活动 + 我在该活动的考勤摘要。
 *
 * @author hengde
 */
@Data
public class MyActivityVO {

    private Long activityId;
    private Long serialNo;
    private String title;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 现场运行状态 0未开始/1进行中/2已结束 */
    private Integer runStatus;
    /** 负责人姓名（志愿者负责人有名；管理团队负责人此列不展示姓名） */
    private List<String> leaderNames;
    /** 到位状态 1正常/2请假/3迟到/4缺席（null未标） */
    private Integer attendStatus;
    private LocalDateTime checkInTime;
    private LocalDateTime checkOutTime;
    /** 服务时长（分钟） */
    private Integer serviceMinutes;
    /** 我在该活动的违规条数（>0 即有违规） */
    private Integer violationCount;
    /** 秘书部确认 0待确认/1已确认 */
    private Integer secretaryStatus;
    /** 积分发放 0未发/1已发 */
    private Integer pointsStatus;
    /** 实发积分 */
    private Integer pointsAward;
}
