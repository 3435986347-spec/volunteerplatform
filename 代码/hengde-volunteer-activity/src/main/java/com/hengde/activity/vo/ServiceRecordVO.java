package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务记录行——既用于志愿者端「我的服务记录」，也用于管理端「服务记录大板块」。
 *
 * @author hengde
 */
@Data
public class ServiceRecordVO {

    private Long attendanceId;
    private Long activityId;
    private Long serialNo;
    private String activityTitle;

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
