package com.hengde.honor.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 排行榜快照的<b>完成标记</b>（V26）：某个「周期 × 板块」是否已冻结。
 *
 * <p><b>为什么需要它，而不是数快照行数</b>：空榜单也是合法的冻结结果——那个月确实没人参加活动。
 * 若把「快照表里查不到行」当成「还没冻结」，空榜就永远退回实时聚合，事后补录一笔该月数据，
 * 这个「历史」月榜会凭空冒出人来，冻结对空榜完全失效。</p>
 *
 * <p>也不用「插一条虚拟志愿者行」当哨兵：那会污染所有读路径（榜单要过滤它、名次要跳过它、
 * 跨域换名查不到它），每个消费方都得记得排除。完成标记与榜单数据是两件事，分表存。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("honor_ranking_snapshot_batch")
public class HonorRankingSnapshotBatch extends BaseEntity {

    /** 周期 1月/2年 */
    private Integer periodType;

    /** 周期标识：月 {@code 2026-07} / 年 {@code 2026} */
    private String periodKey;

    /** 榜单 1次数/2时长/3积分 */
    private Integer rankType;

    /** 本次冻结写入的行数；0 表示空榜，是合法值，仅供核对不参与判定 */
    private Integer rowCount;
}
