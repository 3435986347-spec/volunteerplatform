package com.hengde.auth.constant;

/**
 * 处置措施的能力域。
 *
 * <p><b>需求出处</b>：原型 P109「奖惩记录」的处罚正文——「限制参加活动7天」「限制发布社区」；
 * xlsx Row 73「…限制其使用，包括但不限制于<b>限制其使用指定天数</b>、<b>拒绝其使用本程序</b>。
 * 监察部拥有全部限制能力」。</p>
 *
 * <p><b>为什么按能力域拆而不是复用 {@code volunteer.status}</b>：账号状态只有
 * 0正常/1禁用/2注销 三个全局态，表达不了「只限制参加活动、社区照常」这种处罚，
 * 而那正是 P109 画出来的形态。用禁用账号去实现「限制参加活动7天」会顺带把登录、
 * 查看奖惩记录、提交申诉全部挡掉——申诉权也就没了。</p>
 *
 * @author hengde
 */
public final class SanctionScope {

    private SanctionScope() {
    }

    /** 限制参加活动（P109「限制参加活动7天」）——挡报名与签到 */
    public static final int ACTIVITY = 1;

    /**
     * 限制发布社区（P109「限制发布社区」）。
     *
     * <p>⚠️ <b>当前是空转的</b>：社区（social）模块全项目未建（见 {@code 文档/功能清单.md} 第七节
     * 「社区交流 ⬜ 未实现」），没有可限的对象。取值先定下来是为了让 P109 画到的处罚能<b>如实记录</b>，
     * 而不是被迫塞进别的能力域。social 上线时必须回到
     * {@code SanctionQueryService} 把这条闸门真正接上——{@link #assertKnown} 的存在就是为了让
     * 这个待办在代码里留有实体，而不是只活在文档里。</p>
     */
    public static final int COMMUNITY = 2;

    /**
     * 拒绝使用本程序（Row 73）——挡所有业务功能。
     *
     * <p><b>刻意不挡登录，也不挡奖惩记录与申诉入口</b>：Row 41 F 给了志愿者 7 天申诉期，
     * 而申诉在小程序内提交（P109 的「申诉」按钮）。若这条处置连登录一起挡掉，
     * 被处罚最重的人恰恰是唯一无法申诉的人，申诉权形同虚设。</p>
     */
    public static final int ALL = 3;

    /** 中文名，供出参直接展示（前端不必再维护一份映射）。 */
    public static String labelOf(Integer scope) {
        if (scope == null) {
            return "未知";
        }
        return switch (scope) {
            case ACTIVITY -> "限制参加活动";
            case COMMUNITY -> "限制发布社区";
            case ALL -> "拒绝使用本程序";
            default -> "未知";
        };
    }

    /** 落库前校验取值合法——未知能力域一律拒绝，避免写进一条永远不会被任何闸门认出的处置。 */
    public static void assertKnown(Integer scope) {
        if (scope == null || (scope != ACTIVITY && scope != COMMUNITY && scope != ALL)) {
            throw new IllegalArgumentException("未知的处置能力域：" + scope);
        }
    }
}
