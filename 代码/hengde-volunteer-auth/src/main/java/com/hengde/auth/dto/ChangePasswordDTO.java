package com.hengde.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 志愿者设置/修改登录密码入参（需登录态）。
 *
 * <p>首次设密码 {@code oldPassword} 可空；已有密码时必须传且校验通过。</p>
 *
 * @author hengde
 */
@Data
public class ChangePasswordDTO {

    /** 原密码（首次设密码可空；已设过密码则必填） */
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在 6~32 位")
    private String newPassword;
}
