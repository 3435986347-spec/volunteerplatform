package com.hengde.activity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 活动结束后的「到家」名单一行（V83，Row 63）。
 *
 * <p><b>详细地址与坐标只对持 {@code activity:home-address} 的账号下发</b>，其他人只看得到「已到家」与时间——
 * 这条是<b>字段级</b>的，不是前端打码：把地址放进响应再让前端隐藏，等于没挡。</p>
 *
 * @author hengde
 */
@Data
public class HomeConfirmationVO {
    private Long volunteerId;
    private String volunteerName;
    private Long slotId;
    private String slotName;
    @Schema(description = "有没有确认到家")
    private boolean confirmed;
    private LocalDateTime confirmTime;
    @Schema(description = "详细地址：没有 activity:home-address 时为空")
    private String address;
    @Schema(description = "坐标：没有 activity:home-address 时为空")
    private BigDecimal lat;
    private BigDecimal lng;
}
