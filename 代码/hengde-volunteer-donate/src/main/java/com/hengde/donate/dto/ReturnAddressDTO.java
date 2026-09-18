package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 不合格物资的退回收件信息（Row 17 D「退回的话需要志愿者提交收件信息」）。
 *
 * @author hengde
 */
@Data
@Schema(description = "退回收件信息")
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReturnAddressDTO {

    @Schema(description = "收件人")
    @NotBlank(message = "请填写收件人")
    @Size(max = 64, message = "收件人过长")
    private String name;

    @Schema(description = "收件电话（手机号）")
    @NotBlank(message = "请填写收件电话")
    @Pattern(regexp = "1\\d{10}", message = "请填写 11 位手机号")
    private String phone;

    @Schema(description = "收件地址")
    @NotBlank(message = "请填写收件地址")
    @Size(max = 255, message = "收件地址过长")
    private String address;
}
