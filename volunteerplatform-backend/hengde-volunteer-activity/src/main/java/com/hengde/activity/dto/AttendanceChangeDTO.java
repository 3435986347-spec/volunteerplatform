package com.hengde.activity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 组织部申请变更考勤/积分入参（不立即生效，待部长二次审核）。
 *
 * <p>{@code newValue} 按 {@code changeType} 解释：1/2 为 ISO 时间（如 {@code 2026-05-29T10:00:00}），
 * 3 为整数积分。格式由 service 解析校验。</p>
 *
 * @author hengde
 */
@Data
public class AttendanceChangeDTO {

    /** 变更项 1签到时间/2签退时间/3积分 */
    @NotNull(message = "变更项不能为空")
    @Min(value = 1, message = "变更项取值 1签到/2签退/3积分")
    @Max(value = 3, message = "变更项取值 1签到/2签退/3积分")
    private Integer changeType;

    /** 申请新值（时间 ISO 或整数积分） */
    @NotBlank(message = "新值不能为空")
    @Size(max = 64, message = "新值过长")
    private String newValue;

    /** 变更理由 */
    @Size(max = 512, message = "变更理由不超过 512 字")
    private String reason;
}
