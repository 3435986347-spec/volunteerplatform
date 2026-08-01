package com.hengde.activity.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 志愿者自助签退入参：上报当前坐标 + 方式。GPS 距活动坐标 ≤ 签退半径方可签退。
 *
 * <p>字段同 {@link CheckInDTO}（坐标须做范围校验防 lat+360/lng+360 绕过 Haversine 半径），但语义为签退，单独成类。
 * {@code method} 当前仅前端区分扫码/自动，后端不落库（签退坐标仅实时校验、不留存）。</p>
 *
 * @author hengde
 */
@Data
public class CheckOutDTO {

    /**
     * 场次 activity_slot.id（V30 新增，必填）。
     *
     * <p>签退是<b>场次</b>粒度：一人在同一活动报了几场，就分别签退几次。
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

    /** 签退方式 1扫码/2到点自动定位（默认 2）；仅前端区分，后端不落库 */
    private Integer method;
}
