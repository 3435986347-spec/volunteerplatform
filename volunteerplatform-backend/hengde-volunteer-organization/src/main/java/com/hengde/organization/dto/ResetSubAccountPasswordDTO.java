package com.hengde.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重置子账号密码入参。
 *
 * @author hengde
 */
@Data
public class ResetSubAccountPasswordDTO {

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在 6~32 位")
    private String newPassword;
}
