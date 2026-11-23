package com.hengde.activity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 活动补录申请入参（第 3 批·PR3）。
 *
 * <p>按身份证或手机号<b>精确匹配</b>唯一志愿者（至少提供其一；姓名仅作交叉校验），指定时间段算时长。
 * 是否发积分由该活动是否历史活动决定（service 快照），前端不传。</p>
 *
 * @author hengde
 */
@Data
public class BackfillRequestDTO {

    /** 身份证号（与 phone 至少填一个） */
    private String idCard;

    /** 手机号（与 idCard 至少填一个） */
    private String phone;

    /** 姓名（可空，提供则须与匹配到的志愿者一致） */
    private String name;

    /** 补录时间段 activity_slot.id */
    @NotNull(message = "补录时间段不能为空")
    private Long slotId;

    /** 补录理由 */
    @Size(max = 512, message = "补录理由不超过 512 字")
    private String reason;
}
