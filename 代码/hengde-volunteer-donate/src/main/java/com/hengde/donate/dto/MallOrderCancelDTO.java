package com.hengde.donate.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 协会取消兑换单（协会答复五 乙-5）。原因会写进短信与志愿者端的兑换详情。
 *
 * @author hengde
 */
@Data
public class MallOrderCancelDTO {

    @Schema(description = "取消原因（会告诉志愿者）")
    @NotBlank(message = "请填写取消原因")
    @Size(max = 512, message = "取消原因不超过 512 字")
    private String reason;
}
