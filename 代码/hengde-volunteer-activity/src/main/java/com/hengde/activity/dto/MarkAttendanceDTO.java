package com.hengde.activity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 负责人标记到位状态入参。
 *
 * @author hengde
 */
@Data
public class MarkAttendanceDTO {

    /** 到位状态 1正常到位/2请假/3迟到/4缺席 */
    @NotNull(message = "到位状态不能为空")
    private Integer attendStatus;
}
