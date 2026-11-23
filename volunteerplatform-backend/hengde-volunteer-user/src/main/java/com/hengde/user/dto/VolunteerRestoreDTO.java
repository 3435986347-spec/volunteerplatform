package com.hengde.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * 后台恢复已注销的账号（{@code POST /a/user/volunteers/{id}/restore}）。
 * 注销时手机号已经释放、可能已被别人注册，所以恢复时要填一个<b>当前没被占用</b>的手机号。
 *
 * @author hengde
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class VolunteerRestoreDTO {

    /** 恢复后绑定的手机号（本人之后用它收验证码登录） */
    @NotBlank(message = "请填写手机号")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}
