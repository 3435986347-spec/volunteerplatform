package com.hengde.auth.vo;

/**
 * 志愿者「管理团队标记 + 授权」页基础信息（管理端按 id 定位用）。
 *
 * <p>不含 PII（手机号/身份证），仅给标记开关页展示当前姓名与标记态。授权点经
 * {@code /a/organization/volunteers/{id}/permissions} 读取。</p>
 *
 * @param id          志愿者 id
 * @param name        姓名（游客未实名为 null）
 * @param managerFlag 管理团队标记 0否/1是
 * @param registered  是否已实名注册（registerTime 非空）
 * @author hengde
 */
public record VolunteerFlagInfoView(Long id, String name, Integer managerFlag, boolean registered) {
}
