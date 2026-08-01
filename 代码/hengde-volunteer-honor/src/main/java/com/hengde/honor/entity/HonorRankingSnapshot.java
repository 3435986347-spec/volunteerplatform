package com.hengde.honor.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 排行榜快照的一行（V25）。
 *
 * <p>只存<b>已结束</b>周期（月 / 年）的名次；总榜恒为实时聚合，不落表。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("honor_ranking_snapshot")
public class HonorRankingSnapshot extends BaseEntity {

    /** 周期 1月/2年，见 {@code RankPeriodType} */
    private Integer periodType;

    /** 周期标识：月 {@code 2026-07} / 年 {@code 2026} */
    private String periodKey;

    /** 榜单 1次数/2时长/3积分，见 {@code RankType} */
    private Integer rankType;

    /** 志愿者 id */
    private Long volunteerId;

    /** 名次，从 1 起 */
    private Integer rankNo;

    /** 指标值：次数 / 分钟 / 积分，随 rankType 变 */
    private Long metricValue;
}
