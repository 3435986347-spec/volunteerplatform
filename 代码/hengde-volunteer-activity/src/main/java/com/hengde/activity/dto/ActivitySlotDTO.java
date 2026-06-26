package com.hengde.activity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 活动时间段/子项目入参。
 *
 * @author hengde
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActivitySlotDTO {

    /** 项目名称 */
    @NotBlank(message = "项目名称不能为空")
    private String projectName;

    /** 开始时间（精确到分钟） */
    @NotNull(message = "时间段开始时间不能为空")
    private LocalDateTime startTime;

    /** 结束时间 */
    @NotNull(message = "时间段结束时间不能为空")
    private LocalDateTime endTime;

    /** 需求人数（0=不限，不填默认0） */
    @Min(value = 0, message = "需求人数不能为负")
    private Integer needCount;
}
