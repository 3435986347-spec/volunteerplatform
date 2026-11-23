package com.hengde.honor.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 榜单的一行。
 *
 * @author hengde
 */
@Data
@Schema(description = "排行榜条目")
public class RankingEntryVO {

    @Schema(description = "名次，从 1 起")
    private Integer rankNo;

    @Schema(description = "志愿者 id")
    private Long volunteerId;

    /**
     * 志愿者姓名。
     *
     * <p>显示<b>完整姓名</b>而非姓氏——与活动报名详情的既有口径一致（2026-06-27 用户决策）。</p>
     */
    @Schema(description = "志愿者姓名")
    private String volunteerName;

    @Schema(description = "指标值：次数 / 分钟 / 积分，随榜单类型变")
    private Long metricValue;
}
