package com.hengde.honor.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 勋章（后台视角，含审核字段）。
 *
 * @author hengde
 */
@Data
@Schema(description = "勋章定义")
public class MedalVO {

    @Schema(description = "勋章 id")
    private Long id;

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

    @Schema(description = "条件阈值")
    private Long conditionThreshold;

    @Schema(description = "附带积分奖励，0=不发")
    private Integer rewardPoints;

    @Schema(description = "展示排序")
    private Integer sort;

    @Schema(description = "状态 0草稿/1待审核/2已启用/3已驳回/4已停用")
    private Integer status;

    @Schema(description = "状态名")
    private String statusLabel;

    @Schema(description = "样式审核驳回原因")
    private String rejectReason;

    @Schema(description = "样式审核人 admin_user.id")
    private Long reviewBy;

    @Schema(description = "样式审核时间")
    private LocalDateTime reviewTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
