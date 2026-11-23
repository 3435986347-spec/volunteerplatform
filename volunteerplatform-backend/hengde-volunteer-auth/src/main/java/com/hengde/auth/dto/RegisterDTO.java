package com.hengde.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 志愿者实名注册入参。注册前提：已完成微信登录（携带游客 token）。
 *
 * <p>前端可能多带展示用字段，{@code @JsonIgnoreProperties(ignoreUnknown=true)} 兜底
 * （自建 ObjectMapper 默认开 FAIL_ON_UNKNOWN_PROPERTIES，未知字段会直接 400）；
 * 与 user 域 {@code MyProfileUpdateDTO} 同款处理。</p>
 *
 * @author hengde
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
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

    /**
     * 短信验证码。方案A：登录手机号已验证（账号已绑同号 phoneHash）时前端不发码、此项可空，
     * 故不能加 @NotBlank（参数校验先于 Service 执行，会把合法的空码请求直接 400）；
     * 未绑手机号的账号（如微信登录）是否必填由 Service 按绑定状态校验。
     */
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

    /** i志愿者码图片 URL（@JsonProperty 锁 JSON 名，避免 iV→IV bean introspection 歧义——小程序发 "iVolunteerCodeUrl"，Jackson 推断名却是 "ivolunteerCodeUrl"） */
    @JsonProperty("iVolunteerCodeUrl")
    private String iVolunteerCodeUrl;

    /** 头像 URL */
    private String avatarUrl;

    /** 紧急联系人姓名（兼容历史字段；当前注册页只收紧急联系方式，不强制、也不再传此项） */
    private String emergencyContactName;

    /** 紧急联系方式（电话）：身份证未满 18 岁必填；填了则需与本人手机号不同 */
    private String emergencyContactPhone;

    /** 协议手写签名图片 URL */
    @NotBlank(message = "请签署志愿者协议")
    @Size(max = 512, message = "签名图地址过长")
    private String signatureUrl;
}
