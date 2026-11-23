package com.hengde.organization.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改子账号基本信息入参（不含账号/密码/权限）。
 *
 * @author hengde
 */
@Data
public class SubAccountUpdateDTO {

    @Size(max = 32, message = "姓名过长")
    private String realName;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Size(max = 32, message = "部门名过长")
    private String department;
}
