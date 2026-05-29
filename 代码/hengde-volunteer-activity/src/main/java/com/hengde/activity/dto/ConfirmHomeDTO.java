package com.hengde.activity.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 确认到家入参：上报当前坐标。活动结束后点击；超时（结束 1h 后）只记录不拒绝。
 *
 * <p>坐标范围校验同 {@link CheckInDTO}（防三角函数周期性），service 层另有兜底。</p>
 *
 * @author hengde
 */
@Data
public class ConfirmHomeDTO {

    @NotNull(message = "纬度不能为空")
    @DecimalMin(value = "-90", message = "纬度范围 -90~90")
    @DecimalMax(value = "90", message = "纬度范围 -90~90")
    private BigDecimal lat;

    @NotNull(message = "经度不能为空")
    @DecimalMin(value = "-180", message = "经度范围 -180~180")
    @DecimalMax(value = "180", message = "经度范围 -180~180")
    private BigDecimal lng;
}
