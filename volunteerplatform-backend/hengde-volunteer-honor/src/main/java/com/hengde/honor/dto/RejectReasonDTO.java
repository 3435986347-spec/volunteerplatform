package com.hengde.honor.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 审核驳回入参（样式审核与发放审核共用）。
 *
 * @author hengde
 */
@Data
@Schema(description = "驳回原因")
@JsonIgnoreProperties(ignoreUnknown = true)
public class RejectReasonDTO {

    @Schema(description = "驳回原因")
    @Size(max = 512, message = "驳回原因不超过 512 字")
    private String reason;
}
