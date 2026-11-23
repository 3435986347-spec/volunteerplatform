package com.hengde.activity.vo;

/**
 * 排行聚合的一行：{志愿者, 指标值}。供 honor 域做排行榜，是 activity 对外的<b>只读</b>视图。
 *
 * <p>刻意不带姓名——activity 域不持有志愿者姓名（那在 auth 域），由调用方拿 id 去
 * {@code VolunteerQueryService.listNamesByIds} 批量换名，避免本查询里 join 出跨域依赖。</p>
 *
 * <p>{@code metricValue} 用 long：时长榜的单位是分钟，总榜维度下会远超次数与积分的量级。</p>
 *
 * @author hengde
 */
public class RankingRowView {

    private Long volunteerId;

    private Long metricValue;

    public RankingRowView() {
    }

    public RankingRowView(Long volunteerId, Long metricValue) {
        this.volunteerId = volunteerId;
        this.metricValue = metricValue;
    }

    public Long getVolunteerId() {
        return volunteerId;
    }

    public void setVolunteerId(Long volunteerId) {
        this.volunteerId = volunteerId;
    }

    public Long getMetricValue() {
        return metricValue;
    }

    public void setMetricValue(Long metricValue) {
        this.metricValue = metricValue;
    }
}
