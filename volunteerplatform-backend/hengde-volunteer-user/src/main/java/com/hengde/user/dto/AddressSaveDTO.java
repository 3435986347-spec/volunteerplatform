package com.hengde.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增 / 修改收货地址（Row 40）。
 *
 * @author hengde
 */
@Data
@Schema(description = "收货地址")
public class AddressSaveDTO {

    @NotBlank(message = "请填写收件人")
    @Size(max = 32, message = "收件人不超过 32 字")
    private String recvName;

    @NotBlank(message = "请填写收件电话")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "收件电话格式不正确")
    private String recvPhone;

    @Schema(description = "省市区")
    @NotBlank(message = "请选择所在地区")
    @Size(max = 128, message = "所在地区过长")
    private String region;

    @NotBlank(message = "请填写详细地址")
    @Size(max = 255, message = "详细地址不超过 255 字")
    private String detail;
}
