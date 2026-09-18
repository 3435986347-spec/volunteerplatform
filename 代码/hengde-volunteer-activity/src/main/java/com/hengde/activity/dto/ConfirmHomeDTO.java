package com.hengde.activity.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

    /**
     * 场次 activity_slot.id（V30 新增，必填）。
     *
     * <p>考勤是场次粒度（原型 P15：每行「岗位时间 + 签到 + 签退」），
     * 故所有针对某人某次考勤的操作都要指明是哪一场。</p>
     */
    @NotNull(message = "场次不能为空")
    private Long slotId;


    @NotNull(message = "纬度不能为空")
    @DecimalMin(value = "-90", message = "纬度范围 -90~90")
    @DecimalMax(value = "90", message = "纬度范围 -90~90")
    private BigDecimal lat;

    @NotNull(message = "经度不能为空")
    @DecimalMin(value = "-180", message = "经度范围 -180~180")
    @DecimalMax(value = "180", message = "经度范围 -180~180")
    private BigDecimal lng;

    /** 详细地址（Row 63；小程序逆地理编码后带上，可空） */
    @Size(max = 255, message = "地址不超过 255 字")
    private String address;
}
