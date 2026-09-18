package com.hengde.organization.exam.support;

import com.hengde.organization.form.support.QuestionType;

/**
 * 临时负责人考试的状态码与常量（V4 临时负责人考试批，V75）。
 *
 * @author hengde
 */
public final class ExamCodes {

    private ExamCodes() {
    }

    /** 试卷：草稿（能改题、能删） */
    public static final int PAPER_DRAFT = 0;
    /** 试卷：开放中（同一时刻至多一份，{@code uk_active_open}） */
    public static final int PAPER_OPEN = 1;
    /** 试卷：已停止（已交的答卷照常阅卷） */
    public static final int PAPER_CLOSED = 2;

    /** 答卷：待阅卷（有主观题时交卷即落这里；一个人至多一份，{@code uk_active_pending}） */
    public static final int ATTEMPT_PENDING = 1;
    /** 答卷：已出分 */
    public static final int ATTEMPT_GRADED = 2;

    /** 资格来源：考试 */
    public static final int SOURCE_EXAM = 1;

    /** 资格状态（现算，不落库）：有效 */
    public static final int QUALIFICATION_ACTIVE = 1;
    /** 资格状态（现算）：已到期 */
    public static final int QUALIFICATION_EXPIRED = 2;
    /** 资格状态（现算）：已撤销 */
    public static final int QUALIFICATION_REVOKED = 3;

    /** 到期收尾（授予新资格前把过期的旧行关掉）时写的原因；撤销人为空即是它。 */
    public static final String EXPIRED_REASON = "资格到期";

    /** 一份试卷最多几道题 */
    public static final int QUESTIONS_MAX = 100;
    /** 一道题最多几分 */
    public static final int SCORE_MAX = 100;
    /** 参考答案（填空 / 简答）最多几个字 */
    public static final int REFERENCE_MAX = 2000;

    /** 按志愿者串行化交卷 / 阅卷 / 撤销资格（锁在事务之外） */
    public static final String LOCK_PREFIX = "lock:temp-leader:volunteer:";

    /** 考试只用前五种题型（Row 14 D：单选 / 多选 / 判断 / 填空 / 简答）。 */
    public static boolean isExamType(Integer type) {
        return type != null && type >= QuestionType.SINGLE && type <= QuestionType.TEXT;
    }

    /** 客观题（交卷自动判分）：单选 / 多选 / 判断。 */
    public static boolean isObjective(int type) {
        return type == QuestionType.SINGLE || type == QuestionType.MULTI || type == QuestionType.JUDGE;
    }

    public static String paperStatusLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case PAPER_DRAFT -> "草稿";
            case PAPER_OPEN -> "开放中";
            case PAPER_CLOSED -> "已停止";
            default -> "未知";
        };
    }

    public static String attemptStatusLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case ATTEMPT_PENDING -> "待阅卷";
            case ATTEMPT_GRADED -> "已出分";
            default -> "未知";
        };
    }

    public static String qualificationStatusLabel(int s) {
        return switch (s) {
            case QUALIFICATION_ACTIVE -> "有效";
            case QUALIFICATION_EXPIRED -> "已到期";
            case QUALIFICATION_REVOKED -> "已撤销";
            default -> "未知";
        };
    }
}
