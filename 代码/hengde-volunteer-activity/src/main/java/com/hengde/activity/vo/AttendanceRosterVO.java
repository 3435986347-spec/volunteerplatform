package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 负责人视角的志愿者考勤行（名单 + 名字/电话/学校 + 签到签退/到位/时长/违规数）。
 *
 * @author hengde
 */
@Data
public class AttendanceRosterVO {

    private Long volunteerId;
    private String realName;
    private String phone;
    private String school;

    private LocalDateTime checkInTime;
    private Integer checkInMethod;
    private LocalDateTime checkOutTime;
    /** 1正常到位/2请假/3迟到/4缺席（null未标） */
    private Integer attendStatus;
    private Integer serviceMinutes;
    private Integer violationCount;
}
