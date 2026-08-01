package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务记录行——既用于志愿者端「我的服务记录」，也用于管理端「服务记录大板块」。
 *
 * <p><b>V30 起一行 = 一个场次</b>。原型 P97 的服务记录逐条对应一次签到/签退，即一个场次；
 * 同一活动报了上下午两场就是两条记录，仅靠 {@code activityId}/{@code activityTitle} 无法区分，
 * 界面会出现两行完全相同的「活动名 + 时长」，故带上场次标识与岗位时间。</p>
 *
 * @author hengde
 */
@Data
public class ServiceRecordVO {

    private Long attendanceId;
    private Long activityId;
    private Long serialNo;
    private String activityTitle;

    /** 场次 id（V30）——同一活动的多条服务记录靠它区分 */
    private Long slotId;
    /** 场次（岗位）名称 */
    private String slotProjectName;
    private LocalDateTime slotStartTime;
    private LocalDateTime slotEndTime;

    private Long volunteerId;
    /** 志愿者姓名（管理端大板块展示；志愿者端可空） */
    private String volunteerName;

    private LocalDateTime checkInTime;
    private LocalDateTime checkOutTime;
    private Integer serviceMinutes;
    /** 1正常到位/2请假/3迟到/4缺席 */
    private Integer attendStatus;

    /** 秘书部确认 0待确认/1已确认 */
    private Integer secretaryStatus;
    /** 实发积分（未发为 null） */
    private Integer pointsAward;
    /** 积分发放 0未发/1已发 */
    private Integer pointsStatus;
}
