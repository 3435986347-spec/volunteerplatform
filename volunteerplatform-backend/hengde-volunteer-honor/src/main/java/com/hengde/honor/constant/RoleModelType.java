package com.hengde.honor.constant;

/**
 * 榜样类型：个人事迹 / 团队事迹。
 *
 * <p>V43 新增，供小程序端按页签筛选。存量行迁移时一律默认为 {@link #PERSON}——
 * 改造前录入的都是人物事迹，默认成团队会让它们在「团队」页签里凭空冒出来。</p>
 *
 * @author hengde
 */
public final class RoleModelType {

    private RoleModelType() {
    }

    /** 个人 */
    public static final int PERSON = 1;
    /** 团队 */
    public static final int TEAM = 2;

    /**
     * 是否为合法类型码。
     *
     * @param type 类型码
     * @return 合法返回 true
     */
    public static boolean isValid(Integer type) {
        return type != null && (type == PERSON || type == TEAM);
    }

    /**
     * 类型中文名。
     *
     * @param type 类型码
     * @return 中文名；未知码返回「未知」
     */
    public static String labelOf(Integer type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case PERSON -> "个人";
            case TEAM -> "团队";
            default -> "未知";
        };
    }
}
