package com.hengde.data.complaint.support;

/**
 * 投诉建议的类型、状态、进度动作码（V4 投诉建议批）。
 *
 * @author hengde
 */
public final class ComplaintFlow {

    public static final int TYPE_COMPLAINT = 1;
    public static final int TYPE_SUGGESTION = 2;

    /**
     * 类别决定进哪个部门（协会 2026-09-02：活动类 → 组织部 / 协会内部类 → 秘书部 / 社区类 → 宣传部）。
     * 需求表 Row 43 原写「默认到监察部」，监察部一年前已撤销（协会 2026-09-16），以协会答复为准。
     */
    public static final int CATEGORY_ACTIVITY = 1;
    public static final int CATEGORY_INTERNAL = 2;
    public static final int CATEGORY_COMMUNITY = 3;

    public static final int PENDING = 0;
    public static final int PROCESSING = 1;
    public static final int CLOSED = 2;

    public static final int ACT_SUBMIT = 1;
    public static final int ACT_ACCEPT = 2;
    public static final int ACT_TRANSFER = 3;
    public static final int ACT_REPLY = 4;
    public static final int ACT_NOTE = 5;

    public static final int OP_VOLUNTEER = 1;
    public static final int OP_ADMIN = 2;

    private ComplaintFlow() {
    }

    public static boolean isValidType(Integer t) {
        return t != null && (t == TYPE_COMPLAINT || t == TYPE_SUGGESTION);
    }

    public static boolean isValidCategory(Integer c) {
        return c != null && c >= CATEGORY_ACTIVITY && c <= CATEGORY_COMMUNITY;
    }

    public static String categoryLabel(Integer c) {
        if (c == null) {
            return "";
        }
        return switch (c) {
            case CATEGORY_ACTIVITY -> "活动类";
            case CATEGORY_INTERNAL -> "协会内部类";
            case CATEGORY_COMMUNITY -> "社区类";
            default -> "未知";
        };
    }

    public static String typeLabel(Integer t) {
        if (t == null) {
            return "";
        }
        return switch (t) {
            case TYPE_COMPLAINT -> "投诉";
            case TYPE_SUGGESTION -> "建议";
            default -> "未知";
        };
    }

    public static String statusLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case PENDING -> "待受理";
            case PROCESSING -> "处理中";
            case CLOSED -> "已办结";
            default -> "未知";
        };
    }

    public static String actionLabel(Integer a) {
        if (a == null) {
            return "";
        }
        return switch (a) {
            case ACT_SUBMIT -> "提交";
            case ACT_ACCEPT -> "受理";
            case ACT_TRANSFER -> "流转";
            case ACT_REPLY -> "答复办结";
            case ACT_NOTE -> "内部备注";
            default -> "未知";
        };
    }
}
