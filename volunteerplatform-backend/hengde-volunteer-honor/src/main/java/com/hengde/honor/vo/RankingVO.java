package com.hengde.honor.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 一张榜单。
 *
 * @author hengde
 */
@Data
@Schema(description = "排行榜")
public class RankingVO {

    @Schema(description = "榜单类型 1活动次数/2活动时长/3积分")
    private Integer rankType;

    @Schema(description = "榜单类型名，如「活动次数排行」")
    private String rankTypeLabel;

    @Schema(description = "指标单位，如「次」「分钟」「分」，供前端拼在数值后")
    private String unit;

    @Schema(description = "周期类型 1月/2年/3总")
    private Integer periodType;

    @Schema(description = "周期类型名，如「月榜」")
    private String periodTypeLabel;

    @Schema(description = "周期标识：月 2026-07 / 年 2026 / 总榜 all")
    private String periodKey;

    /**
     * 数据来源：true=读的冻结快照，false=按当前数据实时聚合。
     *
     * <p>暴露这个字段是为了让「名次为什么变了」有据可查：当期榜单本就随数据变动，
     * 而往期榜单一旦有了快照就不再变。若某个往期周期返回 false，说明它的快照还没生成过
     * （例如本功能上线前的历史月份），此时的名次会随后续补录/修正而漂移，
     * 管理员可用 {@code POST /a/honor/rankings/snapshots} 把它固化下来。</p>
     */
    @Schema(description = "是否来自冻结快照；false=按当前数据实时聚合")
    private Boolean fromSnapshot;

    @Schema(description = "榜单条目，按名次正序")
    private List<RankingEntryVO> entries;
}
