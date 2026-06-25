package com.hengde.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * 修改/换绑手机号入参（{@code PUT /v/user/phone}，本人改自己）。新手机号需短信验证（scene=change-phone）。
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
}
