package com.hengde.auth.vo;

import java.time.LocalDateTime;

/**
 * 志愿者在社区里的「名片」：昵称、头像、注册时间、账号状态。<b>不含真实姓名、学校、手机号</b>——
 * 社区是公开场合（Row 23 F 真实姓名与学校只给最高权限）。
 *
 * @author hengde
 */
public record VolunteerSocialCardView(Long id, String nickName, String avatarUrl, LocalDateTime registerTime, Integer status) {
}
