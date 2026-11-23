package com.hengde.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建子账号入参。
 *
 * @author hengde
 */
@Data
public class SubAccountCreateDTO {

    @NotBlank(message = "登录账号不能为空")
    @Size(max = 64, message = "登录账号过长")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在 6~32 位")
    private String password;

    @Size(max = 32, message = "姓名过长")
    private String realName;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Size(max = 32, message = "部门名过长")
    private String department;
}
