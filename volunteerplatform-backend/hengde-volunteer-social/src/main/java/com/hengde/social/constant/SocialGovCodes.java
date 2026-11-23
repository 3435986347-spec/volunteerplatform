package com.hengde.social.constant;

import java.util.Set;

/**
 * 社区治理的状态码（V4 社区治理批）。
 *
 * @author hengde
 */
public final class SocialGovCodes {

    private SocialGovCodes() {
    }

    public static final int REVIEW_APPROVED = 1;
    public static final int REVIEW_REJECTED = 2;
    public static final int MAX_REVIEW_LEVELS = 3;

    public static final int LOG_APPROVE = 1;
    public static final int LOG_REJECT = 2;

    public static final int REPORT_TARGET_POST = 1;
    public static final int REPORT_TARGET_COMMENT = 2;
    public static final int REPORT_PENDING = 0;
    public static final int REPORT_UPHELD = 1;
    public static final int REPORT_DISMISSED = 2;
    public static final int ACTION_NONE = 0;
    public static final int ACTION_HIDE = 1;
    public static final int ACTION_DELETE = 2;

    public static final int INTERACT_LIKE = 1;
    public static final int INTERACT_COMMENT = 2;
    public static final int INTERACT_REPLY = 3;
    public static final int INTERACT_FOLLOW = 4;

    /** 社区禁言能开的能力域：2 全部社区写入 / 4 禁止发帖 / 5 禁止评论 / 6 禁止点赞 / 7 禁止私信（不许开活动与「拒绝使用本程序」——那是奖惩的事） */
    public static final Set<Integer> BAN_SCOPES = Set.of(2, 4, 5, 6, 7);

    public static String reviewLabel(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case REVIEW_APPROVED -> "已通过";
            case REVIEW_REJECTED -> "未通过";
            default -> "审核中";
        };
    }

    public static String interactionLabel(Integer type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case INTERACT_LIKE -> "赞了你的帖子";
            case INTERACT_COMMENT -> "评论了你的帖子";
            case INTERACT_REPLY -> "回复了你的评论";
            case INTERACT_FOLLOW -> "关注了你";
            default -> "";
        };
    }
}
