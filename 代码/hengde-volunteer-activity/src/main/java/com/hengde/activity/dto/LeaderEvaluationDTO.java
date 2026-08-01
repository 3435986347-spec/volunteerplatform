package com.hengde.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 负责人对志愿者的评价入参。
 *
 * @author hengde
 */
@Data
public class LeaderEvaluationDTO {

    /**
     * 场次 activity_slot.id（V30 新增，必填）。
     *
     * <p>考勤是场次粒度（原型 P15：每行「岗位时间 + 签到 + 签退」），
     * 故所有针对某人某次考勤的操作都要指明是哪一场。</p>
     */
    @NotNull(message = "场次不能为空")
    private Long slotId;


    /** 评价内容 */
    @NotBlank(message = "评价内容不能为空")
    @Size(max = 512, message = "评价内容不超过 512 字")
    private String evaluation;
}
