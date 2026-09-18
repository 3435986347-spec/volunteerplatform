package com.hengde.enterprise.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 爱心企业积分入参。
 *
 * @author hengde
 */
public final class EnterprisePointDTOs {

    private EnterprisePointDTOs() {
    }

    @Data
    @Schema(description = "后台调整企业积分（兑换企业权益时扣减，或更正）")
    public static class Adjust {
        @NotNull(message = "请填写调整数额")
        @Schema(description = "正数加、负数扣；不能为 0")
        private Integer amount;

        @NotBlank(message = "请填写说明")
        @Size(max = 255, message = "说明不超过 255 字")
        private String remark;

        @NotBlank(message = "缺少幂等键")
        @Pattern(regexp = "^[A-Za-z0-9:._-]{8,64}$", message = "幂等键为 8~64 位字母、数字或 : . _ -")
        @Schema(description = "幂等键：前端每次打开调整弹窗生成一个（重复提交只记一次）")
        private String requestId;
    }

    @Data
    @Schema(description = "给赞助企业商品指派核销员")
    public static class VerifierAssign {
        @NotBlank(message = "请填写核销员手机号")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private String phone;

        @Size(max = 128, message = "备注不超过 128 字")
        private String remark;
    }
}
