package com.hengde.donate.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增 / 修改自提点（全量语义：不传的字段按清空处理）。
 *
 * @author hengde
 */
@Data
public class MallPickupPointDTO {

    @Schema(description = "名称，如「协会办公室」")
    @NotBlank(message = "请填写自提点名称")
    @Size(max = 64, message = "名称不超过 64 字")
    private String name;

    @Schema(description = "地址")
    @NotBlank(message = "请填写自提点地址")
    @Size(max = 255, message = "地址不超过 255 字")
    private String address;

    @Schema(description = "联系电话")
    @Size(max = 32, message = "电话过长")
    private String phone;

    @Schema(description = "开放时间说明，如「工作日 9:00-17:00」")
    @Size(max = 128, message = "开放时间说明不超过 128 字")
    private String openHours;

    @Schema(description = "1 启用（默认）/ 0 停用")
    private Integer enabled;

    @Schema(description = "排序，小的在前")
    private Integer sort;
}
