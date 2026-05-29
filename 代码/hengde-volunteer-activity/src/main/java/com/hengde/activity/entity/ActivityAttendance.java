package com.hengde.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 活动考勤与服务记录（每活动每志愿者一条，闭环核心表）。
 *
 * <p>粒度为活动级（非时间段级）：统一签退、GPS 一次签到都是活动级；岗位时间另从报名行带出。
 * 服务时长 = 签退 − 签到（分钟），缺席/请假置 0。秘书部确认后才汇入服务记录大板块并据以发积分。</p>
 *
 * <p>评价 / 确认到家相关列（{@code leaderEvaluation}/{@code volXxx}/{@code confirmHomeXxx}）由第 2 批填充，
 * 本批仅用签到/签退/到位/时长/秘书确认/积分这些主干列。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("activity_attendance")
public class ActivityAttendance extends BaseEntity {

    /** 活动 activity.id */
    private Long activityId;

    /** 志愿者 volunteer.id */
    private Long volunteerId;

    /** 签到时间 */
    private LocalDateTime checkInTime;

    /** 签到方式 1扫码/2到点自动定位/3负责人确认 */
    private Integer checkInMethod;

    /** 签到登记人（自助=本人 volunteer.id；负责人代标=负责人） */
    private Long checkInBy;

    /** 签到上报纬度 */
    private BigDecimal checkInLat;

    /** 签到上报经度 */
    private BigDecimal checkInLng;

    /** 签退时间（负责人统一点） */
    private LocalDateTime checkOutTime;

    /** 签退登记人 */
    private Long checkOutBy;

    /** 到位状态 1正常到位/2请假/3迟到/4缺席（null未标） */
    private Integer attendStatus;

    /** 服务时长（分钟，签退算出/可后台改） */
    private Integer serviceMinutes;

    /** 志愿者确认到家时间（活动结束1h内，第2批） */
    private LocalDateTime confirmHomeTime;

    /** 确认到家上报纬度（第2批） */
    private BigDecimal confirmHomeLat;

    /** 确认到家上报经度（第2批） */
    private BigDecimal confirmHomeLng;

    /** 负责人对该志愿者评价（第2批） */
    private String leaderEvaluation;

    /** 志愿者对活动评分（第2批） */
    private Integer volActivityScore;

    /** 志愿者对负责人评分（第2批） */
    private Integer volLeaderScore;

    /** 志愿者评价留言（第2批） */
    private String volComment;

    /** 秘书部确认 0待确认/1已确认 */
    private Integer secretaryStatus;

    /** 秘书部确认人 admin_user.id */
    private Long secretaryBy;

    /** 秘书部确认时间 */
    private LocalDateTime secretaryTime;

    /** 实发积分（确认后据 基数×倍率×违规系数 落值） */
    private Integer pointsAward;

    /** 积分发放 0未发/1已发 */
    private Integer pointsStatus;

    /** 积分调整 0正常/1减半/2不发（违规时由发放人定） */
    private Integer pointsFactor;
}
