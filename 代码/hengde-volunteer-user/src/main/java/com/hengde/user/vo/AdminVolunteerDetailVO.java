package com.hengde.user.vo;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 志愿者管理-详情（{@code GET /a/user/volunteers/{id}}）。在列表字段基础上补敏感/扩展资料。
 *
 * <p>身份证仅回尾号（{@link #idTail}）不回全文；紧急联系人电话解密回显。</p>
 *
 * @author hengde
 */
@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
public class AdminVolunteerDetailVO extends AdminVolunteerListVO {

    /** 身份证后 4 位（脱敏展示，前端拼「****」前缀） */
    private String idTail;

    /** 生日 */
    private LocalDate birthday;

    /** 通讯地址 */
    private String address;

    /** 紧急联系人姓名 */
    private String emergencyContactName;

    /** 紧急联系人电话（解密明文） */
    private String emergencyContactPhone;

    /** 紧急联系人合并展示（姓名 电话），供前端单字段直接渲染 */
    private String emergency;

    /** 注册时所签协议版本 */
    private String signedAgreementVersion;
}
