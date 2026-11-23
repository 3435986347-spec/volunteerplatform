package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 负责人「活动详情」：活动概要 + 现场运行态 + 志愿者考勤名单。
 * 签到/签退二维码不在此 VO，分别经 {@code GET /v/activity/managed-activities/{id}/check-in-qr}、
 * {@code .../check-out-qr} 由后端 ZXing 生成 PNG data URL 返回。
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

    /** 「紧急上报 / 联系负责人」预设电话（前端 tel: 拨号；后台 hengde.activity.emergency-phone 配置，未配为 null） */
    private String emergencyPhone;
}
