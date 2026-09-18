package com.hengde.auth.constant;

import java.util.List;

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
     * <p>自 V4 社区核心批起接通：<b>蕴含下面三个细粒度域</b>（发帖 / 评论 / 点赞），社区的三个写入口都挡。</p>
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

    /** 禁止发帖（Row 23 F「禁止发帖几天」，V4 社区核心批）——挡发帖与修改帖子 */
    public static final int COMMUNITY_POST = 4;

    /** 禁止评论（Row 23 F「禁止评论几天」） */
    public static final int COMMUNITY_COMMENT = 5;

    /** 禁止点赞（Row 23 F「禁止点赞几天」） */
    public static final int COMMUNITY_LIKE = 6;

    /** 禁止私信（V4 私信批）——挡发私信，不挡收；与上面三个同属「社区写入」 */
    public static final int COMMUNITY_CHAT = 7;

    /**
     * 哪些能力域的处置会挡住 {@code scope}：它自己 + 蕴含它的。
     *
     * <p><b>蕴含关系只写在这里</b>（V4规划承重条款 1）：{@code ALL} 蕴含一切，{@code COMMUNITY} 蕴含发帖 / 评论 / 点赞。
     * 闸门的两份查询（快照读与当前读）都从这里取集合——少写一处，「拒绝使用」就会管得比「禁言」还少。</p>
     */
    public static List<Integer> implying(int scope) {
        return switch (scope) {
            case COMMUNITY_POST, COMMUNITY_COMMENT, COMMUNITY_LIKE, COMMUNITY_CHAT -> List.of(scope, COMMUNITY, ALL);
            case ALL -> List.of(ALL);
            default -> List.of(scope, ALL);
        };
    }

    /** 中文名，供出参直接展示（前端不必再维护一份映射）。 */
    public static String labelOf(Integer scope) {
        if (scope == null) {
            return "未知";
        }
        return switch (scope) {
            case ACTIVITY -> "限制参加活动";
            case COMMUNITY -> "限制发布社区";
            case ALL -> "拒绝使用本程序";
            case COMMUNITY_POST -> "禁止发帖";
            case COMMUNITY_COMMENT -> "禁止评论";
            case COMMUNITY_LIKE -> "禁止点赞";
            case COMMUNITY_CHAT -> "禁止私信";
            default -> "未知";
        };
    }

    /** 落库前校验取值合法——未知能力域一律拒绝，避免写进一条永远不会被任何闸门认出的处置。 */
    public static void assertKnown(Integer scope) {
        // ⚠️ 新增能力域要同步这里的上界：漏改的话那一档写不进库，而 implying / labelOf 看着都齐全
        if (scope == null || scope < ACTIVITY || scope > COMMUNITY_CHAT) {
            throw new IllegalArgumentException("未知的处置能力域：" + scope);
        }
    }
}
