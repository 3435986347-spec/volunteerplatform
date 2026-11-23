package com.hengde.organization.form;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.form.dto.FormDTOs;
import com.hengde.organization.form.service.FormService;
import com.hengde.organization.form.service.FormSubmissionService;
import com.hengde.organization.form.support.FormFlow;
import com.hengde.organization.form.support.QuestionType;
import com.hengde.organization.form.vo.FormVOs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hengde.organization.form.FormTestSupport.ADMIN;
import static com.hengde.organization.form.FormTestSupport.a;
import static com.hengde.organization.form.FormTestSupport.next;
import static com.hengde.organization.form.FormTestSupport.q;
import static com.hengde.organization.form.FormTestSupport.submit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 问卷引擎（V4 问卷引擎批）：定义、发布、填写的规则与每一条拒绝条件。
 *
 * <p>答案用例专门覆盖「看起来对、其实不该收」的值：选项编号不存在、多选重复、填空里换行、日期越界、
 * <b>文件题塞外链</b>——只喂合法答卷的用例，把校验整个删掉也照样绿。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class FormEngineTest {

    @Autowired
    private FormService formService;
    @Autowired
    private FormSubmissionService submissionService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private JdbcTemplate jdbc;

    // ================= 定义与生命周期 =================

    @Test
    void definition_assignsOptionKeys_andClampsEachType() {
        Long id = formService.create(FormTestSupport.allTypes("七种题型-" + next()), ADMIN);
        FormVOs.Form f = formService.detail(id);
        assertEquals(FormFlow.DRAFT, f.getStatus());
        assertEquals(7, f.getQuestions().size());
        FormVOs.Question single = f.getQuestions().get(0);
        assertEquals(List.of("A", "B", "C"), single.getOptions().stream().map(FormVOs.Option::getKey).toList(),
                "选项编号由服务端按顺序分配");
        assertEquals(20, f.getQuestions().get(3).getMaxLength());
        assertEquals(1000, f.getQuestions().get(4).getMaxLength(), "简答默认 1000 字");
        assertFalse(f.getQuestions().get(5).getRequired());
        assertEquals("2026-01-01", f.getQuestions().get(6).getMinDate());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), f.getQuestions().stream().map(FormVOs.Question::getSort).toList());

        assertMessage("第 1 题的选项要有 2–50 个", () -> formService.create(
                form("一个选项", q(QuestionType.SINGLE, "只有一个", List.of("是"))), ADMIN));
        assertMessage("第 1 题有重复的选项", () -> formService.create(
                form("重复选项", q(QuestionType.MULTI, "重复", List.of("甲", " 甲 "))), ADMIN), "去掉首尾空白后算重复");
        assertMessage("第 1 题（填空题）不需要选项", () -> formService.create(
                form("填空带选项", q(QuestionType.FILL, "学校", List.of("甲", "乙"))), ADMIN));
        FormDTOs.QuestionSave tooLong = q(QuestionType.FILL, "学校", null);
        tooLong.setMaxLength(501);
        assertMessage("第 1 题的字数上限要在 1–500 之间", () -> formService.create(form("填空超长", tooLong), ADMIN));
        FormDTOs.QuestionSave badSelect = q(QuestionType.MULTI, "多选", List.of("甲", "乙"));
        badSelect.setMinSelect(2);
        badSelect.setMaxSelect(1);
        assertMessage("第 1 题的最少选择数不能大于最多选择数", () -> formService.create(form("选择数", badSelect), ADMIN));
        FormDTOs.QuestionSave judgeWithFiles = q(QuestionType.JUDGE, "判断", null);
        judgeWithFiles.setMaxFiles(3);
        assertMessage("第 1 题（判断题）不能设置文件个数", () -> formService.create(form("判断配文件", judgeWithFiles), ADMIN));

        FormDTOs.Save managerSingle = form("报名管理团队每人一次", q(QuestionType.TEXT, "理由", null));
        managerSingle.setScene(FormFlow.SCENE_MANAGER_APPLICATION);
        managerSingle.setSingleSubmit(true);
        assertTrue(assertThrows(BusinessException.class, () -> formService.create(managerSingle, ADMIN))
                .getMessage().contains("驳回后可以再申请"));
    }

    @Test
    void lifecycle_questionsFreezeAfterPublish_copyToEdit() {
        Long id = formService.create(form("生命周期-" + next(), q(QuestionType.TEXT, "旧题", null)), ADMIN);
        formService.update(id, form("改过的草稿", q(QuestionType.FILL, "新题一", null), q(QuestionType.JUDGE, "新题二", null)));
        assertEquals(List.of("新题一", "新题二"),
                formService.detail(id).getQuestions().stream().map(FormVOs.Question::getTitle).toList(), "草稿改题整批替换");

        formService.publish(id, ADMIN);
        assertMessage("只有草稿可以发布（当前：收集中）", () -> formService.publish(id, ADMIN));
        assertTrue(assertThrows(BusinessException.class, () -> formService.update(id,
                form("发布后改题", q(QuestionType.TEXT, "不许改", null)))).getMessage().startsWith("只有草稿可以修改（当前：收集中）"));
        assertMessage("只有草稿可以删除（当前：收集中）", () -> formService.delete(id));

        Long copy = formService.copy(id, ADMIN);
        FormVOs.Form c = formService.detail(copy);
        assertEquals(FormFlow.DRAFT, c.getStatus());
        assertTrue(c.getTitle().endsWith("（副本）"));
        assertEquals(List.of("新题一", "新题二"), c.getQuestions().stream().map(FormVOs.Question::getTitle).toList());
        formService.delete(copy);

        formService.close(id);
        assertMessage("只有收集中的问卷可以停止（当前：已停止）", () -> formService.close(id));

        FormDTOs.Save expired = form("截止已过-" + next(), q(QuestionType.TEXT, "题", null));
        expired.setStartTime(LocalDateTime.now().minusDays(2));
        expired.setEndTime(LocalDateTime.now().minusDays(1));
        Long expiredId = formService.create(expired, ADMIN);
        assertMessage("截止时间已经过了，请先修改截止时间", () -> formService.publish(expiredId, ADMIN));
    }

    @Test
    void sceneBoundForms_onlyOneCollectingAtATime_generalFormsAreUnlimited() {
        closeCollecting(FormFlow.SCENE_EXCELLENCE);
        Long first = formService.create(scene(FormFlow.SCENE_EXCELLENCE, "评优一-" + next()), ADMIN);
        Long second = formService.create(scene(FormFlow.SCENE_EXCELLENCE, "评优二-" + next()), ADMIN);
        formService.publish(first, ADMIN);
        assertMessage("「评优评先」已经有一份正在收集的问卷，请先停止它再发布", () -> formService.publish(second, ADMIN));
        formService.close(first);
        formService.publish(second, ADMIN);

        Long g1 = formService.create(form("通用一-" + next(), q(QuestionType.TEXT, "题", null)), ADMIN);
        Long g2 = formService.create(form("通用二-" + next(), q(QuestionType.TEXT, "题", null)), ADMIN);
        formService.publish(g1, ADMIN);
        formService.publish(g2, ADMIN);
        formService.close(second);
    }

    // ================= 填写 =================

    @Test
    void submit_normalizesEveryType_andAdminSeesReadableAnswers() {
        Long id = publish(FormTestSupport.allTypes("规范化-" + next()));
        List<FormVOs.Question> qs = formService.detail(id).getQuestions();
        Long me = FormTestSupport.volunteer(volunteerMapper, true);
        String file = FormTestSupport.ownFileUrl(".pdf");

        Long sid = submissionService.submit(id, me, submit(List.of(
                a(qs.get(0).getId(), "B"),
                a(qs.get(1).getId(), List.of("D", "A")),
                a(qs.get(2).getId(), true),
                a(qs.get(3).getId(), "  雷州一中  "),
                a(qs.get(4).getId(), "第一行\n第二行"),
                a(qs.get(5).getId(), List.of(file)),
                a(qs.get(6).getId(), "2026-10-01"))));

        String stored = jdbc.queryForObject("SELECT answers_json FROM org_form_submission WHERE id = ?", String.class, sid);
        assertTrue(stored.contains("[\"A\",\"D\"]"), "多选按选项顺序存：" + stored);
        assertTrue(stored.contains("\"雷州一中\""), "填空去首尾空白：" + stored);

        FormVOs.Submission detail = submissionService.detailForAdmin(sid);
        Map<Integer, String> display = detail.getAnswers().stream()
                .collect(Collectors.toMap(FormVOs.AnswerView::getSort, FormVOs.AnswerView::getDisplay));
        assertEquals("客路", display.get(1));
        assertEquals("摄影、急救", display.get(2));
        assertEquals("是", display.get(3));
        assertEquals(file, display.get(6));
        assertEquals("2026-10-01", display.get(7));
        assertTrue(detail.getVolunteerName().startsWith("问卷填写人"));

        FormSubmissionService.ExportTable t = submissionService.exportTable(id);
        assertEquals(List.of("提交编号", "姓名", "手机号", "提交时间", "1. 你来自哪里"), t.head().subList(0, 5));
        assertEquals(1, t.rows().size());
        assertEquals("摄影、急救", t.rows().get(0).get(5));

        assertTrue(submissionService.listAvailable(me, page()).getRecords().stream()
                .filter(f -> f.getId().equals(id)).findFirst().orElseThrow().getSubmitted(), "列表里标出「已提交」");
        assertMessage("你已经提交过这份问卷了", () -> submissionService.submit(id, me, submit(List.of(
                a(qs.get(0).getId(), "A"), a(qs.get(1).getId(), List.of("A")), a(qs.get(2).getId(), false),
                a(qs.get(3).getId(), "x"), a(qs.get(4).getId(), "x"), a(qs.get(6).getId(), "2026-10-01")))));
    }

    @Test
    void submit_rejectsValuesThatLookRightButAreNot() {
        Long id = publish(FormTestSupport.allTypes("拒绝-" + next()));
        List<FormVOs.Question> qs = formService.detail(id).getQuestions();
        Long me = FormTestSupport.volunteer(volunteerMapper, true);

        assertMessage("第 1 题「你来自哪里」是必答题", () -> submissionService.submit(id, me, submit(List.of())));
        assertMessage("答卷里有不属于这份问卷的题目，请刷新后重新填写",
                () -> submissionService.submit(id, me, submit(List.of(a(-1L, "A")))));
        assertMessage("同一道题提交了两次答案", () -> submissionService.submit(id, me,
                submit(List.of(a(qs.get(0).getId(), "A"), a(qs.get(0).getId(), "B")))));
        assertMessage("第 1 题「你来自哪里」选了不存在的选项", () -> answerOnly(id, me, qs, 0, "Z"));
        assertMessage("第 1 题「你来自哪里」的答案格式不正确", () -> answerOnly(id, me, qs, 0, 1));
        assertMessage("第 2 题「擅长什么」重复选择了同一个选项", () -> answerOnly(id, me, qs, 1, List.of("A", "A")));
        assertMessage("第 2 题「擅长什么」最多只能选 3 项", () -> answerOnly(id, me, qs, 1, List.of("A", "B", "C", "D")));
        assertMessage("第 3 题「是否参加过志愿活动」的答案格式不正确", () -> answerOnly(id, me, qs, 2, "true"));
        assertMessage("第 4 题「学校」是填空题，不能换行", () -> answerOnly(id, me, qs, 3, "雷州\n一中"));
        assertMessage("第 4 题「学校」不能超过 20 字", () -> answerOnly(id, me, qs, 3, "一".repeat(21)));
        assertMessage("第 6 题「简历附件」的文件无效，请重新上传",
                () -> answerOnly(id, me, qs, 5, List.of("https://evil.example.com/cv.pdf")), "外链不收");
        assertMessage("第 6 题「简历附件」的文件无效，请重新上传",
                () -> answerOnly(id, me, qs, 5, List.of("[oss-disabled]/avatar/20260917/" + "a".repeat(32) + ".png")),
                "别的目录的文件也不收");
        assertMessage("第 6 题「简历附件」最多上传 2 个文件", () -> answerOnly(id, me, qs, 5,
                List.of(FormTestSupport.ownFileUrl(".pdf"), FormTestSupport.ownFileUrl(".pdf"), FormTestSupport.ownFileUrl(".pdf"))));
        assertMessage("第 7 题「可以开始的日期」的日期格式应为 yyyy-MM-dd", () -> answerOnly(id, me, qs, 6, "2026/10/01"));
        assertMessage("第 7 题「可以开始的日期」不能晚于 2027-12-31", () -> answerOnly(id, me, qs, 6, "2028-01-01"));
        assertEquals(0, count(id), "被拒的答卷一份都不该落库");
    }

    @Test
    void submit_eligibility_windowScenesAndRegistration() {
        Long me = FormTestSupport.volunteer(volunteerMapper, true);
        Long guest = FormTestSupport.volunteer(volunteerMapper, false);

        Long registeredOnly = publish(form("须实名-" + next(), q(QuestionType.TEXT, "题", null)));
        Long textId = formService.detail(registeredOnly).getQuestions().get(0).getId();
        assertMessage("请先完成实名注册再填写", () -> submissionService.submit(registeredOnly, guest,
                submit(List.of(a(textId, "游客")))));

        FormDTOs.Save open = form("游客可填-多次-" + next(), q(QuestionType.TEXT, "题", null));
        open.setRequireRegistered(false);
        open.setSingleSubmit(false);
        Long openId = publish(open);
        Long openText = formService.detail(openId).getQuestions().get(0).getId();
        submissionService.submit(openId, guest, submit(List.of(a(openText, "第一次"))));
        submissionService.submit(openId, guest, submit(List.of(a(openText, "第二次"))));
        assertEquals(2, submissionService.mySubmissions(openId, guest).size(), "不限次数的问卷可以交两次");

        FormDTOs.Save later = form("还没开始-" + next(), q(QuestionType.TEXT, "题", null));
        later.setStartTime(LocalDateTime.now().plusDays(1));
        Long laterId = publish(later);
        Long laterText = formService.detail(laterId).getQuestions().get(0).getId();
        assertMessage("问卷还没有开始收集", () -> submissionService.submit(laterId, me, submit(List.of(a(laterText, "x")))));
        assertFalse(submissionService.listAvailable(me, page()).getRecords().stream().anyMatch(f -> f.getId().equals(laterId)),
                "还没开始的问卷不出现在可填列表里");

        formService.close(registeredOnly);
        assertMessage("问卷不存在或已停止收集", () -> submissionService.submit(registeredOnly, me,
                submit(List.of(a(textId, "停止后")))));
        assertMessage("问卷不存在或已停止收集", () -> submissionService.detailForVolunteer(registeredOnly, me));

        closeCollecting(FormFlow.SCENE_MANAGER_APPLICATION);
        FormDTOs.Save manager = scene(FormFlow.SCENE_MANAGER_APPLICATION, "报名问卷-" + next());
        Long managerId = publish(manager);
        Long managerText = formService.detail(managerId).getQuestions().get(0).getId();
        assertMessage("这份问卷要在「报名管理团队」里填写", () -> submissionService.submit(managerId, me,
                submit(List.of(a(managerText, "直接交")))), "否则会留下没有申请的孤儿答卷");
        assertFalse(submissionService.listAvailable(me, page()).getRecords().stream().anyMatch(f -> f.getId().equals(managerId)));
        assertEquals(managerId, submissionService.currentForScene(FormFlow.SCENE_MANAGER_APPLICATION, me).getId());
        formService.close(managerId);
        assertNull(submissionService.currentForScene(FormFlow.SCENE_MANAGER_APPLICATION, me));
    }

    // ---------------- helpers ----------------

    private Long publish(FormDTOs.Save dto) {
        Long id = formService.create(dto, ADMIN);
        formService.publish(id, ADMIN);
        return id;
    }

    private void closeCollecting(int scene) {
        jdbc.update("UPDATE org_form SET status = 2 WHERE scene = ? AND status = 1", scene);
    }

    private void answerOnly(Long formId, Long volunteerId, List<FormVOs.Question> qs, int index, Object value) {
        // 其余必答题给合法值，只让这一题出问题
        Object[] ok = {"A", List.of("A"), true, "学校", "介绍", null, "2026-10-01"};
        List<FormDTOs.Answer> answers = new java.util.ArrayList<>();
        for (int i = 0; i < qs.size(); i++) {
            Object v = i == index ? value : ok[i];
            if (v != null) {
                answers.add(a(qs.get(i).getId(), v));
            }
        }
        submissionService.submit(formId, volunteerId, submit(answers));
    }

    private int count(Long formId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM org_form_submission WHERE form_id = ?", Integer.class, formId);
    }

    private static FormDTOs.Save form(String title, FormDTOs.QuestionSave... questions) {
        FormDTOs.Save d = new FormDTOs.Save();
        d.setTitle(title);
        d.setQuestions(List.of(questions));
        return d;
    }

    private static FormDTOs.Save scene(int scene, String title) {
        FormDTOs.Save d = form(title, q(QuestionType.TEXT, "为什么想加入", null));
        d.setScene(scene);
        return d;
    }

    private static PageQuery page() {
        PageQuery q = new PageQuery();
        q.setPage(1);
        q.setSize(100);
        return q;
    }

    private static void assertMessage(String expected, org.junit.jupiter.api.function.Executable action) {
        assertMessage(expected, action, null);
    }

    private static void assertMessage(String expected, org.junit.jupiter.api.function.Executable action, String why) {
        assertEquals(expected, assertThrows(BusinessException.class, action, why).getMessage(), why);
    }
}
