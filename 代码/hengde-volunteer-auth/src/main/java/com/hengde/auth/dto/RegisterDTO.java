package com.hengde.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 志愿者实名注册入参。注册前提：已完成微信登录（携带游客 token）。
 *
 * @author hengde
 */
@Data
public class RegisterDTO {

    /** 姓名 */
    @NotBlank(message = "姓名不能为空")
    private String realName;

    /** 身份证号 */
    @NotBlank(message = "身份证号不能为空")
    private String idCardNo;

    /** 手机号 */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 短信验证码 */
    @NotBlank(message = "验证码不能为空")
    private String smsCode;

    /** 政治面貌 code（见 PoliticalStatus） */
    @NotNull(message = "政治面貌不能为空")
    private Integer politicalStatus;

    /** 学校 */
    private String school;

    /** 年级 code（见 Grade） */
    private Integer grade;

    /** 通讯地址 */
    private String address;

    /** i志愿者码图片 URL */
    private String iVolunteerCodeUrl;

    /** 头像 URL */
    private String avatarUrl;

    /** 紧急联系人姓名（未满 18 岁必填） */
    private String emergencyContactName;

    /** 紧急联系人电话（未满 18 岁必填，且需与本人手机号不同） */
    private String emergencyContactPhone;

    /** 协议手写签名图片 URL */
    @NotBlank(message = "请签署志愿者协议")
    private String signatureUrl;
}
