package com.hengde.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 后台凭短信验证码重置密码入参。
 *
 * @author hengde
 */
@Data
public class AdminResetPasswordDTO {

    /** 账号绑定的手机号 */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 短信验证码 */
    @NotBlank(message = "验证码不能为空")
    private String smsCode;

    /** 新密码 */
    @NotBlank(message = "新密码不能为空")
    private String newPassword;
}
