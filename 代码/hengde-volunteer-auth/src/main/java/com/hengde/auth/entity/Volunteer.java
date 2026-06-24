package com.hengde.auth.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.constant.Gender;
import com.hengde.common.constant.Grade;
import com.hengde.common.constant.PoliticalStatus;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 志愿者（C 端用户）实体。
 *
 * <p>微信登录即建行（游客态，{@link #registerTime} 为 null）；实名注册后填齐资料并置 registerTime。
 * 身份证号、手机号、紧急联系人电话存 AES-GCM 密文，另存 HMAC 哈希列供查重/查询，详见
 * {@link com.hengde.common.crypto.CryptoUtil}。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("volunteer")
public class Volunteer extends BaseEntity {

    /** 微信小程序 openid */
    private String openid;

    /** 微信 unionid（多端） */
    private String unionid;

    /** 姓名 */
    private String realName;

    /** 身份证号（AES-GCM 密文） */
    private String idCardNo;

    /** 身份证 HMAC（查重/判断已注册） */
    private String idCardHash;

    /** 手机号（AES-GCM 密文） */
    private String phone;

    /** 手机号 HMAC（精确搜/换绑查重/手机号登录定位） */
    private String phoneHash;

    /** 登录密码（BCrypt 密文，V20）；null=未设密码。设密码后可用「手机号+密码」登录 */
    private String password;

    /** 性别（可由身份证解析） */
    private Gender gender;

    /** 生日（身份证解析） */
    private LocalDate birthday;

    /** 政治面貌 */
    private PoliticalStatus politicalStatus;

    /** 学校 */
    private String school;

    /** 年级 */
    private Grade grade;

    /** 通讯地址 */
    private String address;

    /** i志愿者码图片（用户自传） */
    @TableField("i_volunteer_code_url")
    private String iVolunteerCodeUrl;

    /** 头像 */
    private String avatarUrl;

    /** 紧急联系人姓名 */
    private String emergencyContactName;

    /** 紧急联系人电话（AES-GCM 密文） */
    private String emergencyContactPhone;

    /** 协议手写签名图片 */
    private String signatureUrl;

    /** 注册时所签志愿者协议版本（V17；合规留痕） */
    private String signedAgreementVersion;

    /** 职位（后台设置，前端名字下展示） */
    private String position;

    /** 管理团队标记 0否/1是（V11；参加活动积分按管理团队倍率 ×1.2。报名管理团队/审批为预留功能） */
    private Integer managerFlag;

    /** 管理团队标记操作人 admin_user.id（V13 审计） */
    private Long managerFlagBy;

    /** 管理团队标记最近操作时间（V13 审计） */
    private LocalDateTime managerFlagTime;

    /** 所属分队（organization 域） */
    private Long squadId;

    /** 账号状态：见 {@link com.hengde.common.constant.UserStatus}（0正常/1禁用/2注销） */
    private Integer status;

    /** 实名注册时间；null=游客，非空=已实名志愿者 */
    private LocalDateTime registerTime;

    /** 会员费到期日（预留占位） */
    private LocalDate membershipExpireDate;
}
