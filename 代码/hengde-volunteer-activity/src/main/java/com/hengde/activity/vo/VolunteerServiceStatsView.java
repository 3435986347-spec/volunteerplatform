package com.hengde.activity.vo;

/**
 * 志愿者服务统计聚合视图：供 user 域「志愿者管理」列表/详情展示服务时长、积分、参与活动次数。
 *
 * <p>跨模块只读出参（仿 auth 的 {@code VolunteerDisplayView} 用 record）。由
 * {@link com.hengde.activity.service.ServiceRecordService#batchStatsByVolunteerIds} 一次性批量聚合，
 * 避免调用方逐人查库（N+1）。</p>
 *
 * <ul>
 *     <li>{@code activityCount}：参与过的活动数（按 activity_id 去重计数，每活动每人一条考勤行）。</li>
 *     <li>{@code confirmedMinutes}：已由秘书部确认（secretary_status=1）的服务时长之和（分钟）。</li>
 *     <li>{@code grantedPoints}：已发放（points_status=1）的积分之和。</li>
 * </ul>
 *
 * @author hengde
 */
public record VolunteerServiceStatsView(Long volunteerId, int activityCount, int confirmedMinutes, int grantedPoints) {

    /** 全 0 的空统计，供「无任何考勤记录」的志愿者占位（列表 best-effort）。 */
    public static VolunteerServiceStatsView empty(Long volunteerId) {
        return new VolunteerServiceStatsView(volunteerId, 0, 0, 0);
    }
}
