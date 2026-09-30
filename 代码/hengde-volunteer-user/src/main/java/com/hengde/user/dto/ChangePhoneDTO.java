package com.hengde.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * 修改/换绑手机号入参（{@code PUT /v/user/phone}，本人改自己）。<b>原手机号与新手机号都要短信验证</b>
 * （两条都用 scene=change-phone）；还没绑过手机号的账号第一次绑定只验新号。
 *
 * @author hengde
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class ChangePhoneDTO {

    /** 新手机号 */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 新手机号的短信验证码（scene=change-phone） */
    @NotBlank(message = "验证码不能为空")
    private String smsCode;

    /**
     * 原手机号的短信验证码（scene=change-phone，发到当前绑定的号）。账号已绑手机号时必填——
     * 只验新号的话，拿到登录态的人就能把号换成自己的、从而拿走整个账号。
     */
    private String oldSmsCode;
}
