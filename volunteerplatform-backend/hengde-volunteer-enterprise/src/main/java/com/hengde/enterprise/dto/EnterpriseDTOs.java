package com.hengde.enterprise.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 爱心企业入参（V4 爱心企业批）。Bean Validation 只挡格式，规则在服务层（信用代码校验位、唯一性、状态）。
 *
 * @author hengde
 */
public final class EnterpriseDTOs {

    private EnterpriseDTOs() {
    }

    static final String PHONE = "^1[3-9]\\d{9}$";
    static final String USERNAME = "^[A-Za-z0-9_]{4,32}$";

    @Data
    @Schema(description = "发短信验证码")
    public static class SmsCode {
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = PHONE, message = "手机号格式不正确")
        private String phone;

        @NotBlank(message = "请指定场景")
        @Schema(description = "enterprise-register 入驻注册 / enterprise-password-reset 找回密码")
        private String scene;
    }

    @Data
    @Schema(description = "企业入驻注册（Row 15：头像、企业名称、信用代码、企业介绍、项目负责人、电话验证码、账号密码）")
    public static class Register {
        @NotBlank(message = "请填写企业名称")
        @Size(max = 100, message = "企业名称不超过 100 字")
        private String name;

        @NotBlank(message = "请填写统一社会信用代码")
        @Size(max = 18, message = "统一社会信用代码是 18 位")
        private String creditCode;

        @Schema(description = "头像 / 照片（先经企业端或后台上传得到；注册时可以不传，审核前补上）")
        @Size(max = 512)
        private String logoUrl;

        @Size(max = 2000, message = "企业介绍不超过 2000 字")
        private String intro;

        @Size(max = 255, message = "地址不超过 255 字")
        private String address;

        @Size(max = 32, message = "对外电话不超过 32 位")
        private String contactPhone;

        @NotBlank(message = "请填写项目负责人")
        @Size(max = 32, message = "项目负责人不超过 32 字")
        private String leaderName;

        @NotBlank(message = "请填写负责人手机号")
        @Pattern(regexp = PHONE, message = "手机号格式不正确")
        private String leaderPhone;

        @NotBlank(message = "请填写短信验证码")
        private String smsCode;

        @NotBlank(message = "请填写登录账号")
        @Pattern(regexp = USERNAME, message = "登录账号为 4~32 位字母、数字或下划线")
        private String username;

        @NotBlank(message = "请填写密码")
        @Size(min = 6, max = 32, message = "密码长度需在 6~32 位")
        private String password;
    }

    @Data
    @Schema(description = "企业登录")
    public static class Login {
        @NotBlank(message = "请填写登录账号")
        private String username;
        @NotBlank(message = "请填写密码")
        private String password;
    }

    @Data
    @Schema(description = "凭负责人手机验证码重置密码")
    public static class ResetPassword {
        @NotBlank(message = "请填写登录账号")
        private String username;
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = PHONE, message = "手机号格式不正确")
        private String phone;
        @NotBlank(message = "验证码不能为空")
        private String smsCode;
        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 32, message = "密码长度需在 6~32 位")
        private String newPassword;
    }

    @Data
    @Schema(description = "修改密码")
    public static class ChangePassword {
        @NotBlank(message = "请填写原密码")
        private String oldPassword;
        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 32, message = "密码长度需在 6~32 位")
        private String newPassword;
    }

    @Data
    @Schema(description = "企业修改自己的资料：头像 / 介绍 / 地址 / 对外电话随时可改；企业名称 / 信用代码 / 项目负责人只在待审核或被驳回时可改（传空＝不改）")
    public static class Profile {
        @Size(max = 100, message = "企业名称不超过 100 字")
        private String name;
        @Size(max = 18, message = "统一社会信用代码是 18 位")
        private String creditCode;
        @Size(max = 32, message = "项目负责人不超过 32 字")
        private String leaderName;
        @Size(max = 512)
        private String logoUrl;
        @Size(max = 2000, message = "企业介绍不超过 2000 字")
        private String intro;
        @Size(max = 255, message = "地址不超过 255 字")
        private String address;
        @Size(max = 32, message = "对外电话不超过 32 位")
        private String contactPhone;
    }

    @Data
    @Schema(description = "后台注册企业账号（Row 15 F，直接为正常状态，负责人手机号不走验证码）")
    public static class AdminCreate {
        @NotBlank(message = "请填写企业名称")
        @Size(max = 100)
        private String name;
        @NotBlank(message = "请填写统一社会信用代码")
        @Size(max = 18)
        private String creditCode;
        @Size(max = 512)
        private String logoUrl;
        @Size(max = 2000)
        private String intro;
        @Size(max = 255)
        private String address;
        @Size(max = 32)
        private String contactPhone;
        @NotBlank(message = "请填写项目负责人")
        @Size(max = 32)
        private String leaderName;
        @NotBlank(message = "请填写负责人手机号")
        @Pattern(regexp = PHONE, message = "手机号格式不正确")
        private String leaderPhone;
        @NotBlank(message = "请填写登录账号")
        @Pattern(regexp = USERNAME, message = "登录账号为 4~32 位字母、数字或下划线")
        private String username;
        @NotBlank(message = "请填写初始密码")
        @Size(min = 6, max = 32, message = "密码长度需在 6~32 位")
        private String password;
    }

    @Data
    @Schema(description = "填写原因（驳回 / 暂停）")
    public static class Reason {
        @NotBlank(message = "请填写原因")
        @Size(max = 255, message = "原因不超过 255 字")
        private String reason;
    }
}
