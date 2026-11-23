package com.hengde.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 后台已登录修改密码入参。
 *
 * @author hengde
 */
@Data
public class AdminChangePasswordDTO {

    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    private String newPassword;
}
