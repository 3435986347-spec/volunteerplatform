package com.hengde.auth.vo;

/**
 * 志愿者「能否接受权益授予」的当前读视图。
 *
 * <p>由 {@code VolunteerQueryService.getGrantEligibilityForShare} 在事务内以 {@code FOR SHARE}
 * 读出，供勋章发放审核这类<b>在审核那一刻才产生权益</b>的场景复核资格用：发起时校验过的实名与
 * 账号状态，到审核时可能已经变了，而快照读看不到。</p>
 *
 * <p>不含 PII（手机号/身份证），只给出判定结论与状态码，便于调用方组织提示文案。</p>
 *
 * @param id         志愿者 id
 * @param name       姓名（游客未实名为 null）
 * @param registered 是否已实名注册（{@code register_time} 非空）
 * @param active     账号是否正常（未禁用、未注销）
 * @param status     原始状态码，见 {@code UserStatus}；便于提示「已禁用」还是「已注销」
 * @author hengde
 */
public record VolunteerGrantEligibilityView(Long id, String name, boolean registered,
                                            boolean active, Integer status) {
}
