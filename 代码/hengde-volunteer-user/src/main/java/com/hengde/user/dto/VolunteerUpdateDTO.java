package com.hengde.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 修改志愿者资料入参（{@code PUT /a/user/volunteers/{id}}，全量更新可编辑字段）。
 *
 * <p><b>不含</b> managerFlag / 权限（走 organization 域的标记/授权页）、不含 status（走 PATCH status）。
 * 手机号若变更，service 侧会重算密文 + phoneHash 并查重。性别/政治面貌/年级传 code。</p>
 *
 * @author hengde
 */
@Getter
@Setter
public class VolunteerUpdateDTO {

    @NotBlank(message = "姓名不能为空")
    @Size(max = 50, message = "姓名过长")
    private String realName;

    /** 手机号明文：非空则重算密文+hash 并查重；留空/null 则<b>清空</b>主手机号与 phoneHash（全量 PUT 语义） */
    private String phone;

    /** 性别 code 0未知/1男/2女；null=清空 */
    @Min(value = 0, message = "性别取值非法")
    @Max(value = 2, message = "性别取值非法")
    private Integer gender;

    /** 政治面貌 code 1~5；null=清空 */
    @Min(value = 1, message = "政治面貌取值非法")
    @Max(value = 5, message = "政治面貌取值非法")
    private Integer political;

    @Size(max = 100, message = "学校名过长")
    private String school;

    /** 年级 code 1~18；null=清空 */
    @Min(value = 1, message = "年级取值非法")
    @Max(value = 18, message = "年级取值非法")
    private Integer grade;

    /** 归属分队 id（可空=未归属） */
    private Long squadId;

    @Size(max = 50, message = "紧急联系人姓名过长")
    private String emergencyContactName;

    /** 紧急联系人电话明文：非空则加密存储；留空/null 则<b>清空</b>该密文（与主手机号清空语义一致） */
    private String emergencyContactPhone;
}
