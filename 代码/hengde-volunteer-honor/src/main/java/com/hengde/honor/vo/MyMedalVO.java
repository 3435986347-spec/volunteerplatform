package com.hengde.honor.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 志愿者视角的一枚勋章：是否已获得 + 获取进度。
 *
 * <p><b>进度不依赖自动发放引擎</b>——三种阈值的当前值在既有服务里都读得到，
 * 展示进度与自动判定是两件事。手动授予类（{@code conditionType=0}）没有进度，
 * 相关字段为 null，前端据此不显示进度条。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "我的勋章 / 勋章进度")
public class MyMedalVO {

    @Schema(description = "勋章 id")
    private Long medalId;

    @Schema(description = "勋章名称")
    private String name;

    @Schema(description = "图标 URL")
    private String iconUrl;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "获取条件 0手动/1累计时长/2累计次数/3累计积分")
    private Integer conditionType;

    @Schema(description = "获取条件名")
    private String conditionTypeLabel;

    @Schema(description = "条件阈值；手动授予为 null")
    private Long conditionThreshold;

    @Schema(description = "附带积分奖励，0=不发")
    private Integer rewardPoints;

    @Schema(description = "是否已获得（仅统计已生效的发放）")
    private Boolean owned;

    @Schema(description = "获得时间；未获得为 null")
    private LocalDateTime grantTime;

    @Schema(description = "当前进度值；手动授予类为 null")
    private Long currentValue;

    @Schema(description = "进度百分比 0~100，已封顶；手动授予类为 null")
    private Integer progressPercent;
}
