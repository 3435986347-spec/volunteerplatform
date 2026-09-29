package com.hengde.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 申请注销（{@code POST /v/user/deactivation}）。验证码发到本人手机号（scene=account-deactivate）。
 *
 * @author hengde
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class DeactivationRequestDTO {

    /** 本人手机号收到的验证码（先调 POST /v/auth/sms/codes，scene=account-deactivate） */
    @NotBlank(message = "请填写短信验证码")
    private String smsCode;
}
