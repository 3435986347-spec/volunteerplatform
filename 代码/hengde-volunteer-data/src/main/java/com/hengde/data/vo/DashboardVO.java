package com.hengde.data.vo;

import lombok.Getter;
import lombok.Setter;

/**
 * 数据看板头部统计（{@code GET /a/data/dashboard} 与 {@code GET /v/data/dashboard} 共用）。
 *
 * <p>对应需求：注册志愿者人数 / 活动场次 / 总服务时长 / 参与人次 / 管理团队人数 / 分队数量。
 * 纯聚合数字（非敏感明细），跨域只读拼装；待办计数不在此（由各自权限受控列表接口的 total 提供）。</p>
 *
 * @author hengde
 */
@Getter
@Setter
public class DashboardVO {

    /** 注册志愿者人数（已实名，register_time 非空） */
    private long registeredVolunteers;

    /** 活动场次（已发布 + 已结束） */
    private long activityCount;

    /** 总服务时长（小时，由秘书已确认分钟数换算，保留 1 位小数） */
    private double totalServiceHours;

    /** 总服务时长（分钟，原始值，便于前端自行换算/核对） */
    private long totalServiceMinutes;

    /** 参与人次（有签到记录的考勤行数） */
    private long participationCount;

    /** 管理团队人数（manager_flag=1） */
    private long managerCount;

    /** 分队数量 */
    private long squadCount;
}
