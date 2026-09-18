package com.hengde.organization.form.support;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.organization.form.dto.FormDTOs;
import com.hengde.organization.form.vo.FormVOs;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 题目定义与答案的校验、规范化（<b>无状态</b>；考试批共用题型与这份答案格式校验——V4规划 D3）。
 *
 * <p>三件事都在这里、只写一次：</p>
 * <ol>
 *   <li><b>题目定义</b>：选项编号由服务端按顺序分配（A、B、C…），客户端给的编号一概不收——
 *       否则两个选项同号时，答案「B」指的是哪一个就说不清了；各题型的上限在这里钳住。</li>
 *   <li><b>答案</b>：只认这份问卷的题、一题一答、必答题必须答、值的类型与范围对得上，并<b>规范化</b>
 *       （多选按选项顺序排、文字去首尾空白、日期统一 yyyy-MM-dd）——存进库的只有规范化后的值，导出与展示不必再猜格式。</li>
 *   <li><b>展示文字</b>：选项编号换成选项文字、布尔换成「是 / 否」。</li>
 * </ol>
 *
 * <p>⚠️ <b>文件题的 URL 必须是本系统上传的</b>（由调用方传入判定，通常是 {@code FileStorageService.isOwnUpload}）：
 * 不校验的话，答卷里可以塞任意外链，管理员在后台点开的就是别人的钓鱼页。</p>
 *
 * @author hengde
 */
public final class FormAnswerValidator {

    public static final int OPTIONS_MIN = 2;
    public static final int OPTIONS_MAX = 50;
    public static final int OPTION_LABEL_MAX = 200;
    public static final int FILL_DEFAULT_MAX = 100;
    public static final int FILL_LIMIT = 500;
    public static final int TEXT_DEFAULT_MAX = 1000;
    public static final int TEXT_LIMIT = 5000;
    public static final int FILES_DEFAULT_MAX = 3;
    public static final int FILES_LIMIT = 9;
    public static final int URL_MAX = 512;

    private FormAnswerValidator() {
    }

    /** 解析后的题目：选项与配置已从 JSON 读出。 */
    public record Definition(Long id, int sort, int type, String title, String description, boolean required,
                             List<FormVOs.Option> options, Integer maxLength, Integer minSelect, Integer maxSelect,
                             Integer maxFiles, LocalDate minDate, LocalDate maxDate) {
    }

    /** 规范化后的一道题的答案。 */
    public record Normalized(Long questionId, Object value) {
    }

    // ================= 题目定义 =================

    /**
     * 校验并规范化一道题的定义。返回的 {@link Definition} 没有 id（还没落库）。
     *
     * @param no 题号（从 1 起），只用来拼报错文字
     */
    public static Definition define(FormDTOs.QuestionSave q, int no) {
        String prefix = "第 " + no + " 题";
        if (q == null || !QuestionType.isValid(q.getType())) {
            throw new BusinessException(prefix + "的题型不正确");
        }
        if (!StringUtils.hasText(q.getTitle())) {
            throw new BusinessException(prefix + "没有填写题目");
        }
        int type = q.getType();
        boolean required = !Boolean.FALSE.equals(q.getRequired());
        List<FormVOs.Option> options = null;
        Integer maxLength = null;
        Integer minSelect = null;
        Integer maxSelect = null;
        Integer maxFiles = null;
        LocalDate minDate = null;
        LocalDate maxDate = null;

        if (QuestionType.isChoice(type)) {
            List<String> labels = q.getOptions() == null ? List.of()
                    : q.getOptions().stream().map(s -> s == null ? "" : s.trim()).toList();
            if (labels.size() < OPTIONS_MIN || labels.size() > OPTIONS_MAX) {
                throw new BusinessException(prefix + "的选项要有 " + OPTIONS_MIN + "–" + OPTIONS_MAX + " 个");
            }
            if (labels.stream().anyMatch(s -> s.isEmpty() || s.length() > OPTION_LABEL_MAX)) {
                throw new BusinessException(prefix + "有空选项或选项超过 " + OPTION_LABEL_MAX + " 字");
            }
            if (new HashSet<>(labels).size() != labels.size()) {
                throw new BusinessException(prefix + "有重复的选项");
            }
            options = new ArrayList<>();
            for (int i = 0; i < labels.size(); i++) {
                options.add(new FormVOs.Option(optionKey(i), labels.get(i)));
            }
        } else if (hasChoiceConfig(q)) {
            throw new BusinessException(prefix + "（" + QuestionType.label(type) + "）不需要选项");
        }

        switch (type) {
            case QuestionType.MULTI -> {
                int n = options.size();
                minSelect = q.getMinSelect();
                maxSelect = q.getMaxSelect();
                if (minSelect != null && (minSelect < 1 || minSelect > n)) {
                    throw new BusinessException(prefix + "的最少选择数要在 1–" + n + " 之间");
                }
                if (maxSelect != null && (maxSelect < 1 || maxSelect > n)) {
                    throw new BusinessException(prefix + "的最多选择数要在 1–" + n + " 之间");
                }
                if (minSelect != null && maxSelect != null && minSelect > maxSelect) {
                    throw new BusinessException(prefix + "的最少选择数不能大于最多选择数");
                }
            }
            case QuestionType.FILL, QuestionType.TEXT -> {
                int limit = type == QuestionType.FILL ? FILL_LIMIT : TEXT_LIMIT;
                maxLength = q.getMaxLength() == null ? (type == QuestionType.FILL ? FILL_DEFAULT_MAX : TEXT_DEFAULT_MAX)
                        : q.getMaxLength();
                if (maxLength < 1 || maxLength > limit) {
                    throw new BusinessException(prefix + "的字数上限要在 1–" + limit + " 之间");
                }
            }
            case QuestionType.FILE -> {
                maxFiles = q.getMaxFiles() == null ? FILES_DEFAULT_MAX : q.getMaxFiles();
                if (maxFiles < 1 || maxFiles > FILES_LIMIT) {
                    throw new BusinessException(prefix + "的文件个数上限要在 1–" + FILES_LIMIT + " 之间");
                }
            }
            case QuestionType.DATE -> {
                minDate = q.getMinDate();
                maxDate = q.getMaxDate();
                if (minDate != null && maxDate != null && minDate.isAfter(maxDate)) {
                    throw new BusinessException(prefix + "的最早日期不能晚于最晚日期");
                }
            }
            default -> {
            }
        }
        if (type != QuestionType.MULTI && (q.getMinSelect() != null || q.getMaxSelect() != null)) {
            throw new BusinessException(prefix + "（" + QuestionType.label(type) + "）不能设置选择数");
        }
        if (type != QuestionType.FILL && type != QuestionType.TEXT && q.getMaxLength() != null) {
            throw new BusinessException(prefix + "（" + QuestionType.label(type) + "）不能设置字数上限");
        }
        if (type != QuestionType.FILE && q.getMaxFiles() != null) {
            throw new BusinessException(prefix + "（" + QuestionType.label(type) + "）不能设置文件个数");
        }
        if (type != QuestionType.DATE && (q.getMinDate() != null || q.getMaxDate() != null)) {
            throw new BusinessException(prefix + "（" + QuestionType.label(type) + "）不能设置日期范围");
        }
        return new Definition(null, no, type, q.getTitle().trim(),
                StringUtils.hasText(q.getDescription()) ? q.getDescription().trim() : null, required,
                options, maxLength, minSelect, maxSelect, maxFiles, minDate, maxDate);
    }

    /** A、B、…、Z、AA、AB…（最多 50 个选项，两位足够）。 */
    static String optionKey(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        do {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    private static boolean hasChoiceConfig(FormDTOs.QuestionSave q) {
        return q.getOptions() != null && !q.getOptions().isEmpty();
    }

    /** 选项 JSON（单选 / 多选），其余题型为 null。 */
    public static String optionsJson(Definition d) {
        return d.options() == null ? null : JSONUtil.toJsonStr(d.options());
    }

    /** 配置 JSON；没有配置时为 null。 */
    public static String configJson(Definition d) {
        Map<String, Object> m = new LinkedHashMap<>();
        putIfPresent(m, "maxLength", d.maxLength());
        putIfPresent(m, "minSelect", d.minSelect());
        putIfPresent(m, "maxSelect", d.maxSelect());
        putIfPresent(m, "maxFiles", d.maxFiles());
        putIfPresent(m, "minDate", d.minDate() == null ? null : d.minDate().toString());
        putIfPresent(m, "maxDate", d.maxDate() == null ? null : d.maxDate().toString());
        return m.isEmpty() ? null : JSONUtil.toJsonStr(m);
    }

    private static void putIfPresent(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    /** 从库里的行还原定义。 */
    public static Definition read(Long id, int sort, int type, String title, String description, boolean required,
                                  String optionsJson, String configJson) {
        List<FormVOs.Option> options = null;
        if (StringUtils.hasText(optionsJson)) {
            options = new ArrayList<>();
            JSONArray arr = JSONUtil.parseArray(optionsJson);
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                options.add(new FormVOs.Option(o.getStr("key"), o.getStr("label")));
            }
        }
        JSONObject c = StringUtils.hasText(configJson) ? JSONUtil.parseObj(configJson) : new JSONObject();
        return new Definition(id, sort, type, title, description, required, options,
                c.getInt("maxLength"), c.getInt("minSelect"), c.getInt("maxSelect"), c.getInt("maxFiles"),
                c.getStr("minDate") == null ? null : LocalDate.parse(c.getStr("minDate")),
                c.getStr("maxDate") == null ? null : LocalDate.parse(c.getStr("maxDate")));
    }

    public static FormVOs.Question toVO(Definition d) {
        FormVOs.Question vo = new FormVOs.Question();
        vo.setId(d.id());
        vo.setSort(d.sort());
        vo.setType(d.type());
        vo.setTypeLabel(QuestionType.label(d.type()));
        vo.setTitle(d.title());
        vo.setDescription(d.description());
        vo.setRequired(d.required());
        vo.setOptions(d.options());
        vo.setMaxLength(d.maxLength());
        vo.setMinSelect(d.minSelect());
        vo.setMaxSelect(d.maxSelect());
        vo.setMaxFiles(d.maxFiles());
        vo.setMinDate(d.minDate() == null ? null : d.minDate().toString());
        vo.setMaxDate(d.maxDate() == null ? null : d.maxDate().toString());
        return vo;
    }

    // ================= 答案 =================

    /**
     * 校验并规范化一份答卷。返回按题号排列的答案；没作答的选答题不出现。
     *
     * @param fileUrlAllowed 文件题的 URL 是否可以接受（本系统上传的）
     */
    public static List<Normalized> validate(List<Definition> questions, Collection<FormDTOs.Answer> answers,
                                            Predicate<String> fileUrlAllowed) {
        Map<Long, Object> byQuestion = new LinkedHashMap<>();
        Set<Long> known = questions.stream().map(Definition::id).collect(Collectors.toSet());
        if (answers != null) {
            for (FormDTOs.Answer a : answers) {
                if (a == null || a.getQuestionId() == null || !known.contains(a.getQuestionId())) {
                    throw new BusinessException("答卷里有不属于这份问卷的题目，请刷新后重新填写");
                }
                if (byQuestion.containsKey(a.getQuestionId())) {
                    throw new BusinessException("同一道题提交了两次答案");
                }
                byQuestion.put(a.getQuestionId(), a.getValue());
            }
        }
        List<Normalized> out = new ArrayList<>();
        for (Definition q : questions) {
            Object value = normalize(q, byQuestion.get(q.id()), fileUrlAllowed);
            if (value == null) {
                if (q.required()) {
                    throw new BusinessException(prefix(q) + "是必答题");
                }
                continue;
            }
            out.add(new Normalized(q.id(), value));
        }
        return out;
    }

    /** 规范化一个值；「没作答」返回 null（空串、空数组都算没作答）。 */
    private static Object normalize(Definition q, Object raw, Predicate<String> fileUrlAllowed) {
        if (raw == null) {
            return null;
        }
        String badFormat = prefix(q) + "的答案格式不正确";
        switch (q.type()) {
            case QuestionType.SINGLE -> {
                if (!(raw instanceof String s)) {
                    throw new BusinessException(badFormat);
                }
                if (s.isBlank()) {
                    return null;
                }
                if (q.options().stream().noneMatch(o -> o.getKey().equals(s))) {
                    throw new BusinessException(prefix(q) + "选了不存在的选项");
                }
                return s;
            }
            case QuestionType.MULTI -> {
                List<String> keys = stringList(raw, badFormat);
                if (keys.isEmpty()) {
                    return null;
                }
                if (new HashSet<>(keys).size() != keys.size()) {
                    throw new BusinessException(prefix(q) + "重复选择了同一个选项");
                }
                List<String> ordered = q.options().stream().map(FormVOs.Option::getKey).filter(keys::contains).toList();
                if (ordered.size() != keys.size()) {
                    throw new BusinessException(prefix(q) + "选了不存在的选项");
                }
                if (q.minSelect() != null && ordered.size() < q.minSelect()) {
                    throw new BusinessException(prefix(q) + "至少要选 " + q.minSelect() + " 项");
                }
                if (q.maxSelect() != null && ordered.size() > q.maxSelect()) {
                    throw new BusinessException(prefix(q) + "最多只能选 " + q.maxSelect() + " 项");
                }
                return ordered;
            }
            case QuestionType.JUDGE -> {
                if (!(raw instanceof Boolean b)) {
                    throw new BusinessException(badFormat);
                }
                return b;
            }
            case QuestionType.FILL, QuestionType.TEXT -> {
                if (!(raw instanceof String s)) {
                    throw new BusinessException(badFormat);
                }
                String t = s.trim();
                if (t.isEmpty()) {
                    return null;
                }
                if (q.type() == QuestionType.FILL && (t.indexOf('\n') >= 0 || t.indexOf('\r') >= 0)) {
                    throw new BusinessException(prefix(q) + "是填空题，不能换行");
                }
                if (t.codePointCount(0, t.length()) > q.maxLength()) {
                    throw new BusinessException(prefix(q) + "不能超过 " + q.maxLength() + " 字");
                }
                return t;
            }
            case QuestionType.FILE -> {
                List<String> urls = stringList(raw, badFormat);
                if (urls.isEmpty()) {
                    return null;
                }
                if (urls.size() > q.maxFiles()) {
                    throw new BusinessException(prefix(q) + "最多上传 " + q.maxFiles() + " 个文件");
                }
                if (new HashSet<>(urls).size() != urls.size()) {
                    throw new BusinessException(prefix(q) + "重复上传了同一个文件");
                }
                for (String u : urls) {
                    if (u.isBlank() || u.length() > URL_MAX || !fileUrlAllowed.test(u)) {
                        throw new BusinessException(prefix(q) + "的文件无效，请重新上传");
                    }
                }
                return urls;
            }
            case QuestionType.DATE -> {
                if (!(raw instanceof String s)) {
                    throw new BusinessException(badFormat);
                }
                if (s.isBlank()) {
                    return null;
                }
                LocalDate d;
                try {
                    d = LocalDate.parse(s.trim());
                } catch (DateTimeParseException e) {
                    throw new BusinessException(prefix(q) + "的日期格式应为 yyyy-MM-dd");
                }
                if (q.minDate() != null && d.isBefore(q.minDate())) {
                    throw new BusinessException(prefix(q) + "不能早于 " + q.minDate());
                }
                if (q.maxDate() != null && d.isAfter(q.maxDate())) {
                    throw new BusinessException(prefix(q) + "不能晚于 " + q.maxDate());
                }
                return d.toString();
            }
            default -> throw new BusinessException(badFormat);
        }
    }

    private static List<String> stringList(Object raw, String badFormat) {
        if (!(raw instanceof Collection<?> c)) {
            throw new BusinessException(badFormat);
        }
        List<String> out = new ArrayList<>(c.size());
        for (Object o : c) {
            if (!(o instanceof String s)) {
                throw new BusinessException(badFormat);
            }
            out.add(s);
        }
        return out;
    }

    private static String prefix(Definition q) {
        return "第 " + q.sort() + " 题「" + abbreviate(q.title()) + "」";
    }

    private static String abbreviate(String s) {
        return s.length() <= 20 ? s : s.substring(0, 20) + "…";
    }

    // ================= 存取与展示 =================

    public static String toJson(List<Normalized> answers) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Normalized n : answers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("questionId", n.questionId());
            m.put("value", n.value());
            rows.add(m);
        }
        return JSONUtil.toJsonStr(rows);
    }

    /** 从库里读出答卷：questionId → 规范化值（多选 / 文件为 {@code List<String>}）。 */
    public static Map<Long, Object> fromJson(String json) {
        Map<Long, Object> out = new LinkedHashMap<>();
        if (!StringUtils.hasText(json)) {
            return out;
        }
        JSONArray arr = JSONUtil.parseArray(json);
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Object v = o.get("value");
            out.put(o.getLong("questionId"), v instanceof JSONArray a ? a.toList(String.class) : v);
        }
        return out;
    }

    /** 按题号列出每道题的答案（没答的题也列出、值为空），给详情与导出用。 */
    public static List<FormVOs.AnswerView> views(List<Definition> questions, Map<Long, Object> answers) {
        List<FormVOs.AnswerView> out = new ArrayList<>(questions.size());
        for (Definition q : questions) {
            Object v = answers.get(q.id());
            FormVOs.AnswerView vo = new FormVOs.AnswerView();
            vo.setQuestionId(q.id());
            vo.setSort(q.sort());
            vo.setTitle(q.title());
            vo.setType(q.type());
            vo.setTypeLabel(QuestionType.label(q.type()));
            vo.setValue(v);
            vo.setDisplay(display(q, v));
            out.add(vo);
        }
        return out;
    }

    /** 给人看的文字；没答为空串。 */
    public static String display(Definition q, Object v) {
        if (v == null) {
            return "";
        }
        return switch (q.type()) {
            case QuestionType.SINGLE -> labelOf(q, String.valueOf(v));
            case QuestionType.MULTI -> ((List<?>) v).stream().map(k -> labelOf(q, String.valueOf(k)))
                    .collect(Collectors.joining("、"));
            case QuestionType.JUDGE -> Boolean.TRUE.equals(v) ? "是" : "否";
            case QuestionType.FILE -> ((List<?>) v).stream().map(String::valueOf).collect(Collectors.joining("\n"));
            default -> String.valueOf(v);
        };
    }

    private static String labelOf(Definition q, String key) {
        if (q.options() == null) {
            return key;
        }
        return q.options().stream().filter(o -> Objects.equals(o.getKey(), key)).map(FormVOs.Option::getLabel)
                .findFirst().orElse(key);
    }
}
