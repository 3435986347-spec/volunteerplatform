package com.hengde.activity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 统一签退入参。{@code volunteerIds} 为空 = 对全体已签到未签退者签退；非空 = 仅对指定志愿者签退。
 *
 * @author hengde
 */
@Data
public class BulkCheckOutDTO {

    /**
     * 场次 activity_slot.id（V30 新增，必填）。
     *
     * <p>考勤是场次粒度（原型 P15：每行「岗位时间 + 签到 + 签退」），
     * 故所有针对某人某次考勤的操作都要指明是哪一场。</p>
     */
    @NotNull(message = "场次不能为空")
    private Long slotId;


    /** 指定签退的志愿者 id；null/空表示全体 */
    private List<Long> volunteerIds;
}
