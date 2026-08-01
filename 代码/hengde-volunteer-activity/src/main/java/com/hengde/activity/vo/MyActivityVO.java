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

    /**
     * 场次 activity_slot.id（V30）。
     *
     * <p>「我的活动」是<b>场次</b>粒度：一人在同一活动报了两场，这里就有两条。
     * 依据原型 P15（报名详情每行「岗位时间 + 签到 + 签退」）与 P92
     * （「我的活动」详情为「岗位名称 + 考勤信息」成对出现）。</p>
     */
    private Long slotId;

    /** 岗位/项目名称（activity_slot.project_name，对应原型 P92「岗位名称」） */
    private String slotProjectName;

    /** 该场次起止（对应原型 P15「岗位时间」） */
    private java.time.LocalDateTime slotStartTime;

    private java.time.LocalDateTime slotEndTime;
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
