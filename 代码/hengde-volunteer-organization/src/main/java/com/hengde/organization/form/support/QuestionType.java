package com.hengde.organization.form.support;

/**
 * 题型（V4 问卷引擎批；临时负责人考试批共用这份枚举与答案格式校验，但不共用表——V4规划 D3）。
 *
 * <p>Row 46 / 47 原文列的是「单选题、多选题、判断题、简答题、文件上传、日期选择」六种；
 * 另加「填空」（单行短答），因为「姓名 / 学校 / 电话」这类一行字的题用简答框（多行、上千字）既难填又难导出。</p>
 *
 * @author hengde
 */
public final class QuestionType {

    public static final int SINGLE = 1;
    public static final int MULTI = 2;
    public static final int JUDGE = 3;
    public static final int FILL = 4;
    public static final int TEXT = 5;
    public static final int FILE = 6;
    public static final int DATE = 7;

    private QuestionType() {
    }

    public static boolean isValid(Integer t) {
        return t != null && t >= SINGLE && t <= DATE;
    }

    public static boolean isChoice(Integer t) {
        return t != null && (t == SINGLE || t == MULTI);
    }

    public static String label(Integer t) {
        if (t == null) {
            return "";
        }
        return switch (t) {
            case SINGLE -> "单选题";
            case MULTI -> "多选题";
            case JUDGE -> "判断题";
            case FILL -> "填空题";
            case TEXT -> "简答题";
            case FILE -> "文件上传";
            case DATE -> "日期选择";
            default -> "未知";
        };
    }
}
