package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 驳回原因（商品审核 / 兑换审核共用）。
 *
 * @author hengde
 */
@Data
@Schema(description = "驳回原因")
@JsonIgnoreProperties(ignoreUnknown = true)
public class RejectReasonDTO {

    @Schema(description = "驳回原因")
    @NotBlank(message = "请填写驳回原因")
    @Size(max = 512, message = "驳回原因不超过 512 字")
    private String reason;
}
