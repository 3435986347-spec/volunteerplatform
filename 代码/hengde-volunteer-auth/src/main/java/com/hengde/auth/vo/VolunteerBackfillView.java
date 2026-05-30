package com.hengde.auth.vo;

/**
 * 活动补录用志愿者定位视图：按手机号/身份证精确匹配后回传，<b>不含手机号/身份证等敏感字段</b>。
 *
 * @param id       志愿者 id
 * @param realName 姓名
 * @param school   学校（展示用，可空）
 * @author hengde
 */
public record VolunteerBackfillView(
        Long id,
        String realName,
        String school
) {
}
