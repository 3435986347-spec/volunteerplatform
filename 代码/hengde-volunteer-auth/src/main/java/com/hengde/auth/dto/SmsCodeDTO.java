package com.hengde.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 发送短信验证码入参。
 *
 * @author hengde
 */
@Data
public class SmsCodeDTO {

    /** 手机号 */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /**
     * 场景：register（注册，默认）/ login（手机号验证码登录）/ volunteer-password-reset（忘记密码）/
     * change-phone（换绑手机号）。service 层按白名单校验，越界拒绝。
     */
    private String scene;
}
