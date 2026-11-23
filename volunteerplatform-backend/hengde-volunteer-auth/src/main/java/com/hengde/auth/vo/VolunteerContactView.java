package com.hengde.auth.vo;

/**
 * 志愿者联系方式（导出类场景用）：姓名、明文手机号、i志愿者码链接。
 *
 * <p>与 {@link VolunteerDisplayView} 分开：那是报名列表的展示字段（学校 / 年级 / 性别），
 * 这是「怎么联系到这个人」。</p>
 *
 * @author hengde
 */
public record VolunteerContactView(Long id, String realName, String phone, String volunteerCodeUrl) {
}
