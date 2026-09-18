package com.hengde.organization.exam.support;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.organization.exam.dto.ExamDTOs;
import com.hengde.organization.exam.entity.OrgExamQuestion;
import com.hengde.organization.form.dto.FormDTOs;
import com.hengde.organization.form.support.FormAnswerValidator;
import com.hengde.organization.form.support.QuestionType;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 试题定义与判分（无状态）。题型、选项编号、答案的规范化<b>全部复用问卷的 {@link FormAnswerValidator}</b>（V4规划 D3），
 * 这里只加考试独有的两件事：标准答案、分值。
 *
 * <p><b>标准答案用作答同一套规范化</b>：多选按选项顺序排好再存，判分时两边都是规范化后的列表，直接比相等——
 * 多选<b>全对才得分、不给部分分</b>（Q33 默认）。判断是布尔。填空 / 简答是主观题，一律人工阅卷，参考答案只给阅卷人看。</p>
 *
 * <p><b>考试里每道题都是选答</b>：没答按 0 分，不因为漏答整份交不上去（问卷里「必答」的意思在考试里就是「不答不得分」）。</p>
 *
 * @author hengde
 */
public final class ExamScoring {

    private ExamScoring() {
    }

    /** 一道题的定义 + 分值 + 规范化后的标准答案（参考答案为字符串或 null）。 */
    public record Item(FormAnswerValidator.Definition definition, int score, Object answer) {
        public boolean subjective() {
            return !ExamCodes.isObjective(definition.type());
        }
    }

    /** 校验并规范化一道新题（还没有 id）。 */
    public static Item define(ExamDTOs.QuestionSave q, int no) {
        String prefix = "第 " + no + " 题";
        if (q == null || !ExamCodes.isExamType(q.getType())) {
            throw new BusinessException(prefix + "的题型不正确（考试只有单选 / 多选 / 判断 / 填空 / 简答）");
        }
        if (q.getScore() == null || q.getScore() < 1 || q.getScore() > ExamCodes.SCORE_MAX) {
            throw new BusinessException(prefix + "的分值要在 1–" + ExamCodes.SCORE_MAX + " 之间");
        }
        FormDTOs.QuestionSave f = new FormDTOs.QuestionSave();
        f.setType(q.getType());
        f.setTitle(q.getTitle());
        f.setDescription(q.getDescription());
        f.setRequired(false);
        f.setOptions(q.getOptions());
        f.setMaxLength(q.getMaxLength());
        FormAnswerValidator.Definition d = FormAnswerValidator.define(f, no);
        Object answer;
        if (ExamCodes.isObjective(d.type())) {
            answer = normalizeStandard(d, q.getAnswer(), prefix);
        } else {
            answer = reference(q.getAnswer(), prefix);
        }
        return new Item(d, q.getScore(), answer);
    }

    private static Object normalizeStandard(FormAnswerValidator.Definition d, Object raw, String prefix) {
        // 借一个临时 id 走作答同一套规范化：多选排好序、选项必须存在、判断必须是布尔
        FormAnswerValidator.Definition probe = withId(d, 0L);
        FormDTOs.Answer a = new FormDTOs.Answer();
        a.setQuestionId(0L);
        a.setValue(raw);
        List<FormAnswerValidator.Normalized> n;
        try {
            n = FormAnswerValidator.validate(List.of(probe), List.of(a), url -> false);
        } catch (BusinessException e) {
            throw new BusinessException(prefix + "的标准答案不正确：" + e.getMessage());
        }
        if (n.isEmpty()) {
            throw new BusinessException(prefix + "（" + QuestionType.label(d.type()) + "）要设置标准答案");
        }
        return n.get(0).value();
    }

    private static String reference(Object raw, String prefix) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String s)) {
            throw new BusinessException(prefix + "的参考答案应为文字");
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.codePointCount(0, t.length()) > ExamCodes.REFERENCE_MAX) {
            throw new BusinessException(prefix + "的参考答案不超过 " + ExamCodes.REFERENCE_MAX + " 字");
        }
        return t;
    }

    private static FormAnswerValidator.Definition withId(FormAnswerValidator.Definition d, Long id) {
        return new FormAnswerValidator.Definition(id, d.sort(), d.type(), d.title(), d.description(), false,
                d.options(), d.maxLength(), d.minSelect(), d.maxSelect(), d.maxFiles(), d.minDate(), d.maxDate());
    }

    /** 标准答案存成 {@code {"value": …}}，字符串 / 数组 / 布尔都能原样读回。 */
    public static String answerJson(Object answer) {
        return answer == null ? null : JSONUtil.toJsonStr(Map.of("value", answer));
    }

    public static Object readAnswer(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        Object v = JSONUtil.parseObj(json).get("value");
        return v instanceof JSONArray arr ? arr.toList(String.class) : v;
    }

    /** 从库里的行还原。 */
    public static Item read(OrgExamQuestion q) {
        FormAnswerValidator.Definition d = FormAnswerValidator.read(q.getId(), q.getSort(), q.getQuestionType(),
                q.getTitle(), q.getDescription(), false, q.getOptionsJson(), q.getConfigJson());
        return new Item(d, q.getScore(), readAnswer(q.getAnswerJson()));
    }

    public static List<Item> readAll(List<OrgExamQuestion> rows) {
        List<Item> out = new ArrayList<>(rows.size());
        for (OrgExamQuestion q : rows) {
            out.add(read(q));
        }
        return out;
    }

    /** 客观题得分：全对得满分，否则 0；主观题返回 null（等人工）。 */
    public static Integer objectiveScore(Item item, Object normalizedValue) {
        if (item.subjective()) {
            return null;
        }
        return normalizedValue != null && Objects.equals(item.answer(), normalizedValue) ? item.score() : 0;
    }
}
