package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 作废一张已发出的卷。原因必填——卷是发到志愿者手里的东西，收回来要留得下理由。
 *
 * @author hengde
 */
@Data
@Schema(description = "作废卷")
@JsonIgnoreProperties(ignoreUnknown = true)
public class MallCouponRevokeDTO {

    @Schema(description = "作废原因")
    @NotBlank(message = "请填写作废原因")
    @Size(max = 512, message = "作废原因不超过 512 字")
    private String reason;
}
