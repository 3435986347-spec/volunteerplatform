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

    /**
     * 场次 activity_slot.id（V30 新增，必填）。
     *
     * <p>考勤是场次粒度（原型 P15：每行「岗位时间 + 签到 + 签退」），
     * 故所有针对某人某次考勤的操作都要指明是哪一场。</p>
     */
    @NotNull(message = "场次不能为空")
    private Long slotId;


    /** 到位状态 1正常到位/2请假/3迟到/4缺席 */
    @NotNull(message = "到位状态不能为空")
    private Integer attendStatus;
}
