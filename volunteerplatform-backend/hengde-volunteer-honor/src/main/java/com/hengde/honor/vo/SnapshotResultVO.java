package com.hengde.honor.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 快照生成结果。
 *
 * @author hengde
 */
@Data
@Schema(description = "排行榜快照生成结果")
public class SnapshotResultVO {

    @Schema(description = "周期类型 1月/2年")
    private Integer periodType;

    @Schema(description = "周期类型名")
    private String periodTypeLabel;

    @Schema(description = "周期标识")
    private String periodKey;

    @Schema(description = "本次写入的快照行数")
    private Integer written;

    /**
     * 本次<b>新冻结</b>的板块名。
     *
     * <p>判断「这次干了活没有」要看这个而不是 {@code written}——<b>空榜也是一次有效的冻结</b>
     * （那个周期确实没人上榜），写入 0 行同样把名次定死了。若按行数判断，
     * 无人参加的月份会被当成「没冻结成功」而被定时任务每天重跑。</p>
     */
    @Schema(description = "本次新冻结的榜单名（空榜也算冻结）")
    private List<String> frozen;

    /**
     * 因已有快照而跳过的板块名。
     *
     * <p>非强制模式下，已冻结的周期不会被重算——把跳过的板块回给调用方，
     * 免得管理员看到「写入 0 行」误以为没数据，实际是已经冻结过了。</p>
     */
    @Schema(description = "因已存在快照而跳过的榜单名")
    private List<String> skipped;

    @Schema(description = "是否强制重算（会覆盖已冻结的历史名次）")
    private Boolean forced;
}
