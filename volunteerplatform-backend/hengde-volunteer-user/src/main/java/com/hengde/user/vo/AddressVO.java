package com.hengde.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收货地址（本人查看，电话明文）。
 *
 * @author hengde
 */
@Data
@Schema(description = "收货地址")
public class AddressVO {
    private Long id;
    private String recvName;
    private String recvPhone;
    private String region;
    private String detail;
    @Schema(description = "是否置顶（一人至多一条）")
    private Boolean top;
    private LocalDateTime updateTime;
}
