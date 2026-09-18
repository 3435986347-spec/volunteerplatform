package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 一个快递单（快递公司编码 + 单号）。用于后台登记「退回寄出」。
 *
 * @author hengde
 */
@Data
@Schema(description = "快递单")
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExpressDTO {

    @Schema(description = "快递公司编码")
    @NotBlank(message = "请选择快递公司")
    private String expressCode;

    @Schema(description = "快递单号")
    @NotBlank(message = "请填写快递单号")
    @Size(max = 64, message = "快递单号过长")
    private String expressNo;
}
