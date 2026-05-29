package com.hengde.activity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 志愿者自助签到入参：上报当前坐标 + 方式。GPS 距活动坐标 ≤ 签到半径方可签到。
 *
 * @author hengde
 */
@Data
public class CheckInDTO {

    @NotNull(message = "纬度不能为空")
    private BigDecimal lat;

    @NotNull(message = "经度不能为空")
    private BigDecimal lng;

    /** 签到方式 1扫码/2到点自动定位（默认 2） */
    private Integer method;
}
