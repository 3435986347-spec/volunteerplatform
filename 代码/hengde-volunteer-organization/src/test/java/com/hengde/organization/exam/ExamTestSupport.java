package com.hengde.organization.exam;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.organization.exam.dto.ExamDTOs;
import com.hengde.organization.form.dto.FormDTOs;
import com.hengde.organization.form.support.QuestionType;
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 考试用例的共用造数。
 *
 * <p><b>同一时刻只有一份开放中的试卷是全库约束</b>，用例之间会互相影响：每个用例开放自己的试卷前先 {@link #closeAllOpen}，
 * 并且只断言自己那份的结果。</p>
 *
 * @author hengde
 */
final class ExamTestSupport {

    static final long ADMIN = 7801L;
    static final long GRADER = 7802L;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);

    private ExamTestSupport() {
    }

    static long next() {
        return SEQ.incrementAndGet();
    }

    static Long volunteer(VolunteerMapper mapper, boolean registered) {
        Volunteer v = new Volunteer();
        v.setOpenid("test:exam:" + System.nanoTime() + ":" + next());
        v.setRealName(registered ? "考生" + next() : null);
        v.setStatus(0);
        v.setManagerFlag(0);
        if (registered) {
            v.setRegisterTime(LocalDateTime.now());
        }
        mapper.insert(v);
        return v.getId();
    }

    static void closeAllOpen(JdbcTemplate jdbc) {
        jdbc.update("UPDATE org_exam_paper SET status = 2 WHERE status = 1");
    }

    static ExamDTOs.QuestionSave q(int type, String title, List<String> options, int score, Object answer) {
        ExamDTOs.QuestionSave q = new ExamDTOs.QuestionSave();
        q.setType(type);
        q.setTitle(title);
        q.setOptions(options);
        q.setScore(score);
        q.setAnswer(answer);
        return q;
    }

    /** 只有客观题：单选 30（答 B）+ 多选 40（答 A、C）+ 判断 30（答 true），满分 100。 */
    static ExamDTOs.PaperSave objectivePaper(int passScore, Integer months) {
        List<ExamDTOs.QuestionSave> qs = new ArrayList<>();
        qs.add(q(QuestionType.SINGLE, "现场出现摔伤应先做什么", List.of("拍照", "止血并联系负责人", "离开"), 30, "B"));
        qs.add(q(QuestionType.MULTI, "签到前要确认哪些", List.of("人数", "天气", "集合点"), 40, List.of("C", "A")));
        qs.add(q(QuestionType.JUDGE, "负责人可以提前离场", null, 30, false));
        return paper("客观题试卷" + next(), passScore, months, qs);
    }

    /** 客观 + 主观：单选 20（B）+ 判断 20（true）+ 填空 20 + 简答 40，满分 100。 */
    static ExamDTOs.PaperSave mixedPaper(int passScore, Integer months) {
        List<ExamDTOs.QuestionSave> qs = new ArrayList<>();
        qs.add(q(QuestionType.SINGLE, "集合迟到应当", List.of("不管", "联系负责人", "直接回家"), 20, "B"));
        qs.add(q(QuestionType.JUDGE, "活动开始前要清点人数", null, 20, true));
        qs.add(q(QuestionType.FILL, "紧急电话是", null, 20, "120"));
        qs.add(q(QuestionType.TEXT, "说说你怎么组织一次签到", null, 40, null));
        return paper("主观题试卷" + next(), passScore, months, qs);
    }

    static ExamDTOs.PaperSave paper(String title, int passScore, Integer months, List<ExamDTOs.QuestionSave> qs) {
        ExamDTOs.PaperSave d = new ExamDTOs.PaperSave();
        d.setTitle(title);
        d.setDescription("用例试卷");
        d.setPassScore(passScore);
        d.setQualificationMonths(months);
        d.setQuestions(qs);
        return d;
    }

    static FormDTOs.Answer a(Long questionId, Object value) {
        FormDTOs.Answer a = new FormDTOs.Answer();
        a.setQuestionId(questionId);
        a.setValue(value);
        return a;
    }

    static ExamDTOs.Submit submit(Long paperId, List<FormDTOs.Answer> answers) {
        ExamDTOs.Submit s = new ExamDTOs.Submit();
        s.setPaperId(paperId);
        s.setAnswers(answers);
        return s;
    }

    static ExamDTOs.QuestionScore score(Long questionId, int score) {
        ExamDTOs.QuestionScore s = new ExamDTOs.QuestionScore();
        s.setQuestionId(questionId);
        s.setScore(score);
        return s;
    }

    static ExamDTOs.Grade grade(List<ExamDTOs.QuestionScore> scores, String note) {
        ExamDTOs.Grade g = new ExamDTOs.Grade();
        g.setScores(scores);
        g.setNote(note);
        return g;
    }

    static void assertMessage(String fragment, Executable call) {
        BusinessException e = assertThrows(BusinessException.class, call);
        assertTrue(e.getMessage().contains(fragment), "期望包含「" + fragment + "」，实际：" + e.getMessage());
    }
}
