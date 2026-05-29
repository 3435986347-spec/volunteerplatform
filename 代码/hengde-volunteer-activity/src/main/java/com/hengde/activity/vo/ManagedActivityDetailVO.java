package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 负责人「活动详情」：活动概要 + 现场运行态 + 志愿者考勤名单。
 * 签到/签退二维码由前端按 activityId 生成，后端不返图。
 *
 * @author hengde
 */
@Data
public class ManagedActivityDetailVO {

    private Long activityId;
    private Long serialNo;
    private String title;
    private String location;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 现场运行状态 0未开始/1进行中/2已结束 */
    private Integer runStatus;
    private LocalDateTime actualStartTime;
    private LocalDateTime actualEndTime;
    private List<AttendanceRosterVO> roster;
}
