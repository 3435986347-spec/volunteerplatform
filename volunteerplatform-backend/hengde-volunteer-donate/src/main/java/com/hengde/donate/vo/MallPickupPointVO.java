package com.hengde.donate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 自提点。
 *
 * @author hengde
 */
@Data
public class MallPickupPointVO {

    private Long id;
    private String name;
    private String address;
    private String phone;
    private String openHours;
    @Schema(description = "1 启用 / 0 停用（志愿者端只会拿到启用的）")
    private Integer enabled;
    private Integer sort;
}
