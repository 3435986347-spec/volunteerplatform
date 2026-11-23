package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 负责人视角的志愿者考勤行（名单 + 名字/电话/学校 + 签到签退/到位/时长/违规数）。
 *
 * <p><b>V30 起一行 = 一个「志愿者 × 场次」</b>，同一人报了上下午两场就出两行，各带各的签到签退。
 * 依据 xlsx Row 32 C（负责人页「显示活动场次…活动时间段…是否到位…是否违规」）与原型 P15
 * （报名详情同一人多行、每行各带岗位时间与签到签退、可「筛选：全部时间段」）。</p>
 *
 * @author hengde
 */
@Data
public class AttendanceRosterVO {

    /** 场次 id（V30）——同一志愿者的多行靠它区分 */
    private Long slotId;
    /** 场次（岗位）名称 */
    private String slotProjectName;
    private LocalDateTime slotStartTime;
    private LocalDateTime slotEndTime;

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
