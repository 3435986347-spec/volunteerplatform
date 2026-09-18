package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 指派核销员（V3 由后台指派；企业自助留 V4）。
 *
 * @author hengde
 */
@Data
@Schema(description = "指派核销员")
@JsonIgnoreProperties(ignoreUnknown = true)
public class MallVerifierAssignDTO {

    @Schema(description = "被指派的志愿者 id（须已实名且账号正常）")
    @NotNull(message = "请选择志愿者")
    private Long volunteerId;

    @Schema(description = "备注（如所在门店）")
    @Size(max = 128, message = "备注不超过 128 字")
    private String remark;
}
