package com.hengde.data.complaint.support;

/**
 * 投诉建议的类型、状态、进度动作码（V4 投诉建议批）。
 *
 * @author hengde
 */
public final class ComplaintFlow {

    public static final int TYPE_COMPLAINT = 1;
    public static final int TYPE_SUGGESTION = 2;

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
