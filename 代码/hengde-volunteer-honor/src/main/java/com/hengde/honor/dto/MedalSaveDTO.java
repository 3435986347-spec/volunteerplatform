package com.hengde.honor.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 勋章定义的新增/修改入参。
 *
 * @author hengde
 */
@Data
@Schema(description = "勋章定义")
@JsonIgnoreProperties(ignoreUnknown = true)
public class MedalSaveDTO {

    @Schema(description = "勋章名称")
    @NotBlank(message = "勋章名称不能为空")
    @Size(max = 64, message = "勋章名称不超过 64 字")
    private String name;

    @Schema(description = "图标 URL，经 /a/files/upload?dir=medal 上传")
    @NotBlank(message = "请上传勋章图标")
    @Size(max = 512, message = "图标 URL 过长")
    private String iconUrl;

    @Schema(description = "勋章说明")
    @Size(max = 512, message = "说明不超过 512 字")
    private String description;

    @Schema(description = "获取条件 0手动授予/1累计服务时长(分钟)/2累计活动次数/3累计获得积分")
    @Min(value = 0, message = "获取条件非法")
    @Max(value = 3, message = "获取条件非法")
    private Integer conditionType;

    @Schema(description = "条件阈值；手动授予时留空")
    @Min(value = 1, message = "条件阈值须大于 0")
    private Long conditionThreshold;

    @Schema(description = "附带积分奖励，0=不发")
    @Min(value = 0, message = "积分奖励不能为负")
    private Integer rewardPoints;

    @Schema(description = "展示排序，小的在前")
    private Integer sort;
}
