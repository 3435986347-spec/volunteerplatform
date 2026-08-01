package com.hengde.honor.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 勋章发放记录（后台视角，带勋章名与志愿者姓名）。
 *
 * @author hengde
 */
@Data
@Schema(description = "勋章发放记录")
public class MedalGrantVO {

    @Schema(description = "发放记录 id")
    private Long id;

    @Schema(description = "勋章 id")
    private Long medalId;

    @Schema(description = "勋章名称")
    private String medalName;

    @Schema(description = "图标 URL")
    private String iconUrl;

    @Schema(description = "志愿者 id")
    private Long volunteerId;

    @Schema(description = "志愿者姓名")
    private String volunteerName;

    @Schema(description = "发放方式 1手动/2自动")
    private Integer grantType;

    @Schema(description = "积分奖励（发起时快照值）")
    private Integer rewardPoints;

    @Schema(description = "授予理由")
    private String reason;

    @Schema(description = "状态 0待审核/1已生效/2已驳回")
    private Integer status;

    @Schema(description = "状态名")
    private String statusLabel;

    @Schema(description = "驳回原因")
    private String rejectReason;

    @Schema(description = "发起人 admin_user.id")
    private Long applyBy;

    @Schema(description = "发起时间")
    private LocalDateTime applyTime;

    @Schema(description = "审核人 admin_user.id")
    private Long reviewBy;

    @Schema(description = "审核时间")
    private LocalDateTime reviewTime;
}
