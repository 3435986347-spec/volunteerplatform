package com.hengde.organization.exam;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.exam.entity.OrgTempLeaderQualification;
import com.hengde.organization.exam.service.ExamAttemptService;
import com.hengde.organization.exam.service.ExamPaperService;
import com.hengde.organization.exam.service.TempLeaderQueryService;
import com.hengde.organization.exam.service.TempLeaderService;
import com.hengde.organization.exam.vo.ExamVOs;
import com.hengde.organization.form.support.QuestionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static com.hengde.organization.exam.ExamTestSupport.ADMIN;
import static com.hengde.organization.exam.ExamTestSupport.GRADER;
import static com.hengde.organization.exam.ExamTestSupport.a;
import static com.hengde.organization.exam.ExamTestSupport.assertMessage;
import static com.hengde.organization.exam.ExamTestSupport.grade;
import static com.hengde.organization.exam.ExamTestSupport.q;
import static com.hengde.organization.exam.ExamTestSupport.score;
import static com.hengde.organization.exam.ExamTestSupport.submit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 临时负责人考试（V4 临时负责人考试批）：试卷定义与生命周期 / 客观题自动判分与授予 / 主观题阅卷 / 资格到期、撤销、重考 / 名单与导出。
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class ExamServiceTest {

    @Autowired
    private ExamPaperService paperService;
    @Autowired
    private ExamAttemptService attemptService;
    @Autowired
    private TempLeaderService tempLeaderService;
    @Autowired
    private TempLeaderQueryService queryService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void paperDefinition_standardAnswersScoresAndPassLine() {
        assertMessage("要设置标准答案", () -> paperService.create(ExamTestSupport.paper("缺答案", 10, null,
                List.of(q(QuestionType.SINGLE, "单选", List.of("甲", "乙"), 10, null))), ADMIN));
        assertMessage("标准答案不正确", () -> paperService.create(ExamTestSupport.paper("不存在的选项", 10, null,
                List.of(q(QuestionType.SINGLE, "单选", List.of("甲", "乙"), 10, "C"))), ADMIN));
        assertMessage("标准答案不正确", () -> paperService.create(ExamTestSupport.paper("判断给字符串", 10, null,
                List.of(q(QuestionType.JUDGE, "判断", null, 10, "true"))), ADMIN));
        assertMessage("要设置标准答案", () -> paperService.create(ExamTestSupport.paper("多选空数组", 10, null,
                List.of(q(QuestionType.MULTI, "多选", List.of("甲", "乙"), 10, List.of()))), ADMIN));
        assertMessage("题型不正确", () -> paperService.create(ExamTestSupport.paper("文件题", 10, null,
                List.of(q(QuestionType.FILE, "上传", null, 10, null))), ADMIN));
        assertMessage("分值要在", () -> paperService.create(ExamTestSupport.paper("分值越界", 10, null,
                List.of(q(QuestionType.JUDGE, "判断", null, 101, true))), ADMIN));
        assertMessage("及格线要在 1–30", () -> paperService.create(ExamTestSupport.paper("及格线超满分", 31, null,
                List.of(q(QuestionType.JUDGE, "判断", null, 30, true))), ADMIN));
        assertMessage("参考答案应为文字", () -> paperService.create(ExamTestSupport.paper("参考答案给数字", 5, null,
                List.of(q(QuestionType.TEXT, "简答", null, 10, 42))), ADMIN));

        Long id = paperService.create(ExamTestSupport.objectivePaper(60, 12), ADMIN);
        ExamVOs.Paper p = paperService.detail(id);
        assertEquals(100, p.getTotalScore(), "满分＝各题分值之和");
        assertEquals(3, p.getQuestionCount());
        assertEquals(List.of("A", "C"), p.getQuestions().get(1).getAnswer(), "多选标准答案按选项顺序规范化");
        assertEquals("人数、集合点", p.getQuestions().get(1).getAnswerDisplay());
        assertEquals(Boolean.FALSE, p.getQuestions().get(2).getAnswer());
        assertEquals(0, p.getStatus());
    }

    @Test
    void lifecycle_draftOnlyEdits_oneOpenPaper_copyAndDelete() {
        ExamTestSupport.closeAllOpen(jdbc);
        Long first = paperService.create(ExamTestSupport.objectivePaper(60, null), ADMIN);
        paperService.update(first, ExamTestSupport.objectivePaper(70, 6));
        assertEquals(70, paperService.detail(first).getPassScore());
        paperService.publish(first);
        assertMessage("只有草稿可以修改", () -> paperService.update(first, ExamTestSupport.objectivePaper(10, null)));
        assertMessage("只有草稿可以删除", () -> paperService.delete(first));

        Long second = paperService.create(ExamTestSupport.mixedPaper(60, null), ADMIN);
        assertMessage("已经有一份开放中的试卷", () -> paperService.publish(second));
        paperService.close(first);
        assertMessage("只有开放中的试卷可以停止", () -> paperService.close(first));
        paperService.publish(second);
        paperService.close(second);

        Long copy = paperService.copy(first, ADMIN);
        ExamVOs.Paper c = paperService.detail(copy);
        assertEquals(0, c.getStatus());
        assertEquals(70, c.getPassScore());
        assertEquals(6, c.getQualificationMonths());
        assertEquals(List.of("A", "C"), c.getQuestions().get(1).getAnswer(), "复制带着标准答案");
        paperService.delete(copy);
        assertMessage("试卷不存在", () -> paperService.detail(copy));
    }

    @Test
    void objectiveOnly_autoGradedOnSubmit_passGrantsQualification() {
        ExamTestSupport.closeAllOpen(jdbc);
        Long paperId = paperService.create(ExamTestSupport.objectivePaper(60, null), ADMIN);
        paperService.publish(paperId);
        List<ExamVOs.Question> qs = paperService.detail(paperId).getQuestions();
        Long single = qs.get(0).getId(), multi = qs.get(1).getId(), judge = qs.get(2).getId();

        Long tourist = ExamTestSupport.volunteer(volunteerMapper, false);
        assertMessage("请先完成实名注册", () -> attemptService.submit(tourist, submit(paperId, List.of())));
        assertFalse(attemptService.myExam(tourist).getCanTake());

        Long me = ExamTestSupport.volunteer(volunteerMapper, true);
        ExamVOs.MyExam before = attemptService.myExam(me);
        assertTrue(before.getCanTake());
        assertEquals(3, before.getPaper().getQuestions().size());
        assertNull(before.getPaper().getQuestions().get(0).getAnswer(), "志愿者端不下发标准答案");

        assertMessage("不属于这份试卷", () -> attemptService.submit(me, submit(paperId, List.of(a(999999999L, "A")))));

        // 单选对 30、多选只选对一个 0（全对才得分）、判断没答 0 → 30 分不及格
        ExamVOs.Attempt fail = attemptService.submit(me, submit(paperId, List.of(a(single, "B"), a(multi, List.of("A")))));
        assertEquals(2, fail.getStatus());
        assertEquals(30, fail.getTotalScore());
        assertFalse(fail.getPassed());
        assertFalse(queryService.isTempLeader(me));

        // 重考：多选打乱顺序也算对 → 100 分及格
        ExamVOs.Attempt pass = attemptService.submit(me, submit(paperId,
                List.of(a(single, "B"), a(multi, List.of("C", "A")), a(judge, false))));
        assertEquals(100, pass.getTotalScore());
        assertTrue(pass.getPassed());
        OrgTempLeaderQualification q = queryService.current(me);
        assertNotNull(q);
        assertNull(q.getExpireTime(), "试卷没设有效期＝长期有效");
        assertEquals(pass.getId(), q.getAttemptId());

        assertMessage("已经是活动临时负责人了", () -> attemptService.submit(me, submit(paperId, List.of())));
        ExamVOs.MyExam after = attemptService.myExam(me);
        assertTrue(after.getTempLeader());
        assertFalse(after.getCanTake());
        assertNull(after.getPaper().getQuestions(), "不能考时不下发题目");

        assertEquals(2, attemptService.myAttempts(me, new PageQuery()).getRecords().size());
        paperService.close(paperId);
        Long other = ExamTestSupport.volunteer(volunteerMapper, true);
        assertMessage("已经停止考试", () -> attemptService.submit(other, submit(paperId, List.of())));
    }

    @Test
    void subjective_pendingUntilGraded_thenGrantWithExpiry() {
        ExamTestSupport.closeAllOpen(jdbc);
        Long paperId = paperService.create(ExamTestSupport.mixedPaper(60, 6), ADMIN);
        paperService.publish(paperId);
        List<ExamVOs.Question> qs = paperService.detail(paperId).getQuestions();
        Long single = qs.get(0).getId(), judge = qs.get(1).getId(), fill = qs.get(2).getId(), text = qs.get(3).getId();

        Long me = ExamTestSupport.volunteer(volunteerMapper, true);
        ExamVOs.Attempt pending = attemptService.submit(me, submit(paperId, List.of(a(single, "B"), a(judge, true),
                a(fill, "120"), a(text, "先点名，再核对报名名单"))));
        assertEquals(1, pending.getStatus(), "有主观题：待阅卷");
        assertEquals(40, pending.getObjectiveScore());
        assertNull(pending.getTotalScore());
        assertFalse(queryService.isTempLeader(me));
        assertMessage("正在阅卷", () -> attemptService.submit(me, submit(paperId, List.of())));
        assertTrue(attemptService.myExam(me).getHasPendingAttempt());

        Long attemptId = pending.getId();
        assertMessage("第 4 题还没打分", () -> attemptService.grade(attemptId, grade(List.of(score(fill, 20)), null), GRADER));
        assertMessage("得分要在 0–20", () -> attemptService.grade(attemptId,
                grade(List.of(score(fill, 21), score(text, 30)), null), GRADER));
        assertMessage("只能给这份试卷的主观题", () -> attemptService.grade(attemptId,
                grade(List.of(score(single, 20), score(fill, 20), score(text, 30)), null), GRADER));
        assertMessage("同一道题打了两次分", () -> attemptService.grade(attemptId,
                grade(List.of(score(fill, 20), score(fill, 20), score(text, 30)), null), GRADER));

        ExamVOs.Attempt graded = attemptService.grade(attemptId, grade(List.of(score(fill, 20), score(text, 10)), "简答太简略"), GRADER);
        assertEquals(2, graded.getStatus());
        assertEquals(70, graded.getTotalScore());
        assertTrue(graded.getPassed());
        assertEquals(20, graded.getAnswers().get(2).getScore());
        assertEquals("120", graded.getAnswers().get(2).getAnswerDisplay(), "管理端看得到参考答案");
        assertEquals(20, graded.getAnswers().get(0).getScore(), "客观题逐题得分现算");

        OrgTempLeaderQualification q = queryService.current(me);
        assertNotNull(q);
        assertTrue(Math.abs(ChronoUnit.SECONDS.between(LocalDateTime.now().plusMonths(6), q.getExpireTime())) < 30,
                "有效期＝出分时刻 + 6 个月：" + q.getExpireTime());
        assertMessage("已经出分了", () -> attemptService.grade(attemptId, grade(List.of(score(fill, 0), score(text, 0)), null), GRADER));

        ExamVOs.Attempt mine = attemptService.myAttemptDetail(me, attemptId);
        assertEquals(70, mine.getTotalScore());
        assertNull(mine.getAnswers().get(2).getScore(), "志愿者看不到逐题得分");
        assertNull(mine.getAnswers().get(2).getAnswerDisplay(), "志愿者看不到标准答案");
        Long stranger = ExamTestSupport.volunteer(volunteerMapper, true);
        assertMessage("答卷不存在", () -> attemptService.myAttemptDetail(stranger, attemptId));

        List<ExamVOs.Attempt> queue = attemptService.list(new PageQuery(), 1, paperId, null, null).getRecords();
        assertTrue(queue.stream().noneMatch(x -> x.getId().equals(attemptId)), "出了分就不在阅卷队列");
    }

    @Test
    void qualification_expiryRevokeRetake_listAndExport() {
        ExamTestSupport.closeAllOpen(jdbc);
        Long paperId = paperService.create(ExamTestSupport.objectivePaper(60, 3), ADMIN);
        paperService.publish(paperId);
        List<ExamVOs.Question> qs = paperService.detail(paperId).getQuestions();
        List<com.hengde.organization.form.dto.FormDTOs.Answer> right = List.of(a(qs.get(0).getId(), "B"),
                a(qs.get(1).getId(), List.of("A", "C")), a(qs.get(2).getId(), false));

        Long me = ExamTestSupport.volunteer(volunteerMapper, true);
        attemptService.submit(me, submit(paperId, right));
        OrgTempLeaderQualification first = queryService.current(me);
        assertNotNull(first);

        // 到期：不改任何状态位，按时间现算就不是了；到期的人可以再考
        jdbc.update("UPDATE org_temp_leader_qualification SET expire_time = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), first.getId());
        assertFalse(queryService.isTempLeader(me));
        assertTrue(queryService.filterTempLeaders(List.of(me)).isEmpty());
        assertTrue(attemptService.myExam(me).getCanTake());
        attemptService.submit(me, submit(paperId, right));
        OrgTempLeaderQualification second = queryService.current(me);
        assertNotNull(second, "到期的那一行先收尾，新资格才插得进去");
        assertEquals("资格到期", jdbc.queryForObject("SELECT revoke_reason FROM org_temp_leader_qualification WHERE id = ?",
                String.class, first.getId()));

        // 撤销
        assertMessage("请填写撤销原因", () -> tempLeaderService.revoke(second.getId(), " ", ADMIN));
        tempLeaderService.revoke(second.getId(), "活动评价过低", ADMIN);
        assertFalse(queryService.isTempLeader(me));
        assertMessage("已经撤销或已到期", () -> tempLeaderService.revoke(second.getId(), "再撤一次", ADMIN));
        assertMessage("已经撤销或已到期", () -> tempLeaderService.revoke(first.getId(), "撤已收尾的", ADMIN));
        // 已到期但还没收尾（没人再考、那一行还占着唯一键）的也撤不了：它已经不是临时负责人了
        Long lapsed = ExamTestSupport.volunteer(volunteerMapper, true);
        attemptService.submit(lapsed, submit(paperId, right));
        Long lapsedId = queryService.current(lapsed).getId();
        jdbc.update("UPDATE org_temp_leader_qualification SET expire_time = ? WHERE id = ?", LocalDateTime.now().minusMinutes(1), lapsedId);
        assertMessage("已经撤销或已到期", () -> tempLeaderService.revoke(lapsedId, "撤没收尾的到期行", ADMIN));
        // 撤销之后又过了原定到期时间：状态仍是「撤销」，不能出现在「已到期」筛选里
        jdbc.update("UPDATE org_temp_leader_qualification SET expire_time = ? WHERE id = ?", LocalDateTime.now().minusMinutes(1), second.getId());

        List<ExamVOs.Qualification> history = tempLeaderService.historyOf(me);
        assertEquals(2, history.size());
        assertEquals(3, history.get(0).getStatus(), "撤销");
        assertEquals("活动评价过低", history.get(0).getRevokeReason());
        assertEquals(2, history.get(1).getStatus(), "到期（已收尾的也算到期，不算撤销）");
        assertEquals(100, history.get(0).getAttemptScore());

        // 撤销后可以再考、再获得
        attemptService.submit(me, submit(paperId, right));
        assertTrue(queryService.isTempLeader(me));

        List<Long> activeIds = idsOf(tempLeaderService.list(new PageQuery(), 1, null).getRecords());
        List<Long> expiredIds = idsOf(tempLeaderService.list(new PageQuery(), 2, null).getRecords());
        List<Long> revokedIds = idsOf(tempLeaderService.list(new PageQuery(), 3, null).getRecords());
        assertTrue(activeIds.contains(queryService.current(me).getId()));
        assertTrue(expiredIds.contains(first.getId()) && !activeIds.contains(first.getId()));
        assertTrue(revokedIds.contains(second.getId()) && !expiredIds.contains(second.getId()));

        String name = volunteerMapper.selectById(me).getRealName();
        List<List<String>> rows = tempLeaderService.exportRows(null, name);
        assertEquals(3, rows.size());
        assertEquals(TempLeaderService.exportHead().size(), rows.get(0).size());
        assertEquals("有效", rows.get(0).get(2));
        assertTrue(tempLeaderService.list(new PageQuery(), null, "查无此人" + ExamTestSupport.next()).getRecords().isEmpty());
        assertMessage("状态只能是", () -> tempLeaderService.list(new PageQuery(), 9, null));
    }

    private static List<Long> idsOf(List<ExamVOs.Qualification> rows) {
        List<Long> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.getId()));
        return out;
    }

}
