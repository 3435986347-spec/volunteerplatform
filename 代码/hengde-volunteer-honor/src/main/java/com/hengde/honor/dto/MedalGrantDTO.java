package com.hengde.honor.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发起勋章发放的入参。
 *
 * @author hengde
 */
@Data
@Schema(description = "勋章发放发起")
@JsonIgnoreProperties(ignoreUnknown = true)
public class MedalGrantDTO {

    @Schema(description = "勋章 id")
    @NotNull(message = "请选择勋章")
    private Long medalId;

    @Schema(description = "志愿者 id")
    @NotNull(message = "请选择志愿者")
    private Long volunteerId;

    @Schema(description = "授予理由")
    @Size(max = 512, message = "授予理由不超过 512 字")
    private String reason;
}
