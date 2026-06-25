package com.hengde.user.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 志愿者端「我的资料」（{@code GET /v/user/profile}）：当前登录志愿者本人的完整资料 + 跨域统计/归属。
 *
 * <p>游客（{@code registerTime} 为空）也可取，仅实名相关字段为空、{@link #registered} 为 false。
 * 身份证仅回尾号（{@link #idTail}）不回全文，与管理端详情同口径，避免明文 PII 出网。
 * 字段名对齐小程序 {@code normalizeUserProfile} 读取的别名。</p>
 *
 * @author hengde
 */
@Getter
@Setter
public class MyProfileVO {

    /** 是否已实名（registerTime 非空） */
    private boolean registered;

    /** 姓名（实名后有） */
    private String realName;

    /** 手机号（解密明文，本人可见） */
    private String phone;

    /** 身份证后 4 位（脱敏） */
    private String idTail;

    /** 性别 code 0未知/1男/2女 */
    private Integer gender;

    /** 性别中文 */
    private String genderName;

    /** 生日（身份证解析） */
    private LocalDate birthday;

    /** 政治面貌 code */
    private Integer politicalStatus;

    /** 政治面貌中文 */
    private String politicalStatusName;

    /** 年级 code */
    private Integer grade;

    /** 年级中文 */
    private String gradeName;

    /** 学校 */
    private String school;

    /** 通讯地址 */
    private String address;

    /** 头像 URL */
    private String avatarUrl;

    /** i志愿者码图片 URL（显式锁定 JSON 名，避免 Lombok getter 大小写歧义；前端只读展示用） */
    @JsonProperty("iVolunteerCodeUrl")
    private String iVolunteerCodeUrl;

    /** 职位（后台设置，名字下展示） */
    private String position;

    /** 紧急联系人姓名 */
    private String emergencyContactName;

    /** 紧急联系人电话（解密明文） */
    private String emergencyContactPhone;

    /** 管理团队标记 0否/1是 */
    private Integer managerFlag;

    /** 注册时间（null=游客） */
    private LocalDateTime registerTime;

    /** 注册时所签协议版本 */
    private String signedAgreementVersion;

    /** 已确认服务时长（分钟） */
    private int serviceMinutes;

    /** 已发放积分 */
    private int points;

    /** 参与活动数 */
    private int activityCount;

    /** 所在 ACTIVE 小组名 */
    private String groupName;

    /** 归属分队 id */
    private Long squadId;

    /** 归属分队名 */
    private String squadName;
}
