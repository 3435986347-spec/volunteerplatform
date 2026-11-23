package com.hengde.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 后台账号密码登录入参。
 *
 * @author hengde
 */
@Data
public class AdminLoginDTO {

    @NotBlank(message = "账号不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
