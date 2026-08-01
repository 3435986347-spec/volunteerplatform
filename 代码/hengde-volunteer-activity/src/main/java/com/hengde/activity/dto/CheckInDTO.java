package com.hengde.activity.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 志愿者自助签到入参：上报当前坐标 + 方式。GPS 距活动坐标 ≤ 签到半径方可签到。
 *
 * <p>必须做经纬度范围校验：Haversine 用三角函数、周期性使 {@code lat+360/lng+360} 这类非法坐标
 * 距离≈0，会绕过「距活动 ≤ 半径」。除此处 Bean Validation 外，service 层 {@code checkIn} 再守一道。</p>
 *
 * @author hengde
 */
@Data
public class CheckInDTO {

    /**
     * 场次 activity_slot.id（V30 新增，必填）。
     *
     * <p>签到是<b>场次</b>粒度：一人在同一活动报了几场，就分别签到几次。
     * 依据原型 P15「报名详情」每行「岗位时间 + 签到时间 + 签退时间」。</p>
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

    /** 签到方式 1扫码/2到点自动定位（默认 2） */
    private Integer method;
}
