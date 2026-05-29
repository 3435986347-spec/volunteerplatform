package com.hengde.auth.vo;

import java.time.LocalDate;

/**
 * 志愿者资格档案视图：仅暴露报名资格校验所需的非敏感字段，供 activity 等领域跨模块只读消费。
 *
 * <p>刻意不含身份证/手机号等加密 PII——跨领域只需性别/生日/年级/状态来判断报名资格，
 * 不该把整个 {@code Volunteer} 实体（连同密文列）泄露给其他模块。</p>
 *
 * @param id       志愿者 id
 * @param gender   性别 code：0未知/1男/2女（对应 {@code Gender}）
 * @param birthday 生日（按身份证解析，用于算年龄）
 * @param grade    年级 code（对应 {@code Grade}，null 表示未填）
 * @param status   账号状态：0正常/1禁用/2注销（对应 {@code UserStatus}）
 * @author hengde
 */
public record VolunteerProfileView(
        Long id,
        Integer gender,
        LocalDate birthday,
        Integer grade,
        Integer status
) {
}
