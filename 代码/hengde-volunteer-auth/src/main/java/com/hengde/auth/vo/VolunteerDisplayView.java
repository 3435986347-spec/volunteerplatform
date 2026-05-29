package com.hengde.auth.vo;

/**
 * 志愿者展示信息视图：供管理端报名列表/导出等场景跨模块展示志愿者基本信息。
 *
 * <p>{@code phone} 为**解密后的明文**——后台管理需联系志愿者，解密在 auth 侧（持有 CryptoUtil）完成；
 * 面向前端公示的打码是另一回事，不在此处理。身份证等更敏感字段不在此暴露。</p>
 *
 * @param id       志愿者 id
 * @param realName 姓名
 * @param school   学校
 * @param grade    年级 code（对应 {@code Grade}，null 表示未填）
 * @param gender   性别 code：0未知/1男/2女
 * @param phone    手机号明文（解密后；无则为 null）
 * @author hengde
 */
public record VolunteerDisplayView(
        Long id,
        String realName,
        String school,
        Integer grade,
        Integer gender,
        String phone
) {
}
