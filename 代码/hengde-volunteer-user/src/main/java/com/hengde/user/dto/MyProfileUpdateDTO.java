package com.hengde.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 志愿者端「我的资料」修改入参（{@code PATCH /v/user/profile}，本人改自己）。
 *
 * <p><b>部分更新语义</b>：仅对传入的非空字段更新，未传/留空不动（避免误清空）。可改：头像 / i志愿者码 /
 * 昵称（全局唯一，重名拒绝）/ 学校 / 年级 / 政治面貌 / 通讯地址 / 紧急联系方式。<b>手机号</b>需短信验证、走专用接口
 * {@code PUT /v/user/phone}；<b>姓名/身份证</b>等实名字段「不可以修改」（仅后台超管可改）。
 * 年级/政治面貌传 code。</p>
 *
 * <p>前端历史上会多带 nickName/phone/*Name 等字段，{@code @JsonIgnoreProperties(ignoreUnknown=true)}
 * 兜底忽略（项目自建 ObjectMapper 默认 FAIL_ON_UNKNOWN_PROPERTIES 开启，不忽略会 400）。</p>
 *
 * @author hengde
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class MyProfileUpdateDTO {

    /** 头像 URL */
    @Size(max = 512, message = "头像地址过长")
    private String avatarUrl;

    /** i志愿者码图片 URL（@JsonProperty 锁 JSON 名，避免 iV→IV 等 bean introspection 歧义） */
    @JsonProperty("iVolunteerCodeUrl")
    @Size(max = 512, message = "i志愿者码地址过长")
    private String iVolunteerCodeUrl;

    /** 昵称（全局唯一；非空才改，重名拒绝） */
    @Size(max = 50, message = "昵称过长")
    private String nickName;

    /** 学校 */
    @Size(max = 100, message = "学校名过长")
    private String school;

    /** 通讯地址 */
    @Size(max = 200, message = "地址过长")
    private String address;

    /** 政治面貌 code 1~5（null=不改） */
    @Min(value = 1, message = "政治面貌取值非法")
    @Max(value = 5, message = "政治面貌取值非法")
    private Integer politicalStatus;

    /** 年级 code 1~18（null=不改） */
    @Min(value = 1, message = "年级取值非法")
    @Max(value = 18, message = "年级取值非法")
    private Integer grade;

    /** 紧急联系方式（电话）：非空则需为合法手机号且与本人手机号不同 */
    private String emergencyContactPhone;
}
