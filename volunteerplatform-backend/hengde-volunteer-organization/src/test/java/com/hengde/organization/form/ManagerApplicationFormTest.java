package com.hengde.organization.form;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dto.ManagerApplyDTO;
import com.hengde.organization.biz.service.ManagerApplicationService;
import com.hengde.organization.biz.vo.ManagerApplicationVO;
import com.hengde.organization.form.dto.FormDTOs;
import com.hengde.organization.form.service.FormService;
import com.hengde.organization.form.service.FormSubmissionService;
import com.hengde.organization.form.support.FormFlow;
import com.hengde.organization.form.support.QuestionType;
import com.hengde.organization.form.vo.FormVOs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static com.hengde.organization.form.FormTestSupport.ADMIN;
import static com.hengde.organization.form.FormTestSupport.a;
import static com.hengde.organization.form.FormTestSupport.next;
import static com.hengde.organization.form.FormTestSupport.q;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报名管理团队接入问卷（Row 46「这个板块需预留，类似于一个问卷调查」、Row 44「批量下载」）。
 *
 * <p>钉住三件事：没有问卷时 V23 的申请原样可用；有问卷时答卷与申请<b>同成同败</b>（申请被拒不留孤儿答卷）；
 * 被驳回后可以再申请（问卷不能是「每人一次」，否则第二次申请撞在唯一键上）。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class ManagerApplicationFormTest {

    @Autowired
    private ManagerApplicationService applicationService;
    @Autowired
    private FormService formService;
    @Autowired
    private FormSubmissionService submissionService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void noManagerFormCollecting() {
        jdbc.update("UPDATE org_form SET status = 2 WHERE scene = ? AND status = 1", FormFlow.SCENE_MANAGER_APPLICATION);
    }

    @Test
    void withoutAForm_theV23ApplicationStillWorks_butStaleAnswersAreRefused() {
        Long me = FormTestSupport.volunteer(volunteerMapper, true);
        Long other = FormTestSupport.volunteer(volunteerMapper, true);
        assertNull(submissionService.currentForScene(FormFlow.SCENE_MANAGER_APPLICATION, me));

        Long appId = applicationService.apply(me, dto("想为协会出力", null));
        assertNull(applicationService.detail(appId).getFormSubmissionId());

        assertEquals("「报名管理团队」的问卷已停止收集，请刷新后重新填写", assertThrows(BusinessException.class,
                () -> applicationService.apply(other, dto("照着旧问卷填的", List.of(a(1L, "答案"))))).getMessage(),
                "问卷停了、他却带着答案：默默丢掉他填的东西不对");
    }

    @Test
    void withAForm_answersLandWithTheApplication_andSurviveRejectAndReapply() {
        Long formId = publishManagerForm();
        List<FormVOs.Question> qs = formService.detail(formId).getQuestions();
        Long me = FormTestSupport.volunteer(volunteerMapper, true);
        int before = submissions(formId);

        assertEquals("第 1 题「期望加入的部门」是必答题", assertThrows(BusinessException.class,
                () -> applicationService.apply(me, dto("没填问卷", null))).getMessage());
        assertEquals(before, submissions(formId));

        Long first = applicationService.apply(me, dto("第一次申请", List.of(a(qs.get(0).getId(), "B"),
                a(qs.get(1).getId(), "做过两年支教"))));
        ManagerApplicationVO detail = applicationService.detail(first);
        assertNotNull(detail.getFormSubmissionId());
        assertEquals("宣传部", detail.getFormAnswers().get(0).getDisplay());
        assertEquals("做过两年支教", detail.getFormAnswers().get(1).getDisplay());

        // 已有待审的申请：申请被拒，答卷也不能留下（资格校验排在交答卷之前）
        assertTrue(assertThrows(BusinessException.class, () -> applicationService.apply(me, dto("重复申请",
                List.of(a(qs.get(0).getId(), "A"), a(qs.get(1).getId(), "x"))))).getMessage().contains("待审核"));
        assertEquals(before + 1, submissions(formId), "申请被拒时不留答卷");

        applicationService.reject(first, "名额已满", ADMIN);
        Long second = applicationService.apply(me, dto("第二次申请", List.of(a(qs.get(0).getId(), "A"),
                a(qs.get(1).getId(), "又做了一年"))));
        assertEquals("组织部", applicationService.detail(second).getFormAnswers().get(0).getDisplay(),
                "驳回后再申请：问卷不是「每人一次」，第二份答卷照常落库");

        List<List<String>> rows = applicationService.exportRows(null).stream()
                .filter(r -> r.get(0).equals(String.valueOf(second)) || r.get(0).equals(String.valueOf(first))).toList();
        assertEquals(2, rows.size());
        assertEquals(ManagerApplicationService.EXPORT_HEAD.size(), rows.get(0).size());
        assertTrue(rows.stream().anyMatch(r -> r.get(10).contains("1. 期望加入的部门：宣传部")), rows.toString());
        assertTrue(rows.stream().anyMatch(r -> r.get(6).equals("已驳回")));
        formService.close(formId);
    }

    private Long publishManagerForm() {
        FormDTOs.Save d = new FormDTOs.Save();
        d.setScene(FormFlow.SCENE_MANAGER_APPLICATION);
        d.setTitle("报名管理团队问卷-" + next());
        d.setQuestions(List.of(q(QuestionType.SINGLE, "期望加入的部门", List.of("组织部", "宣传部", "秘书部")),
                q(QuestionType.TEXT, "相关经历", null)));
        Long id = formService.create(d, ADMIN);
        formService.publish(id, ADMIN);
        return id;
    }

    private int submissions(Long formId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM org_form_submission WHERE form_id = ?", Integer.class, formId);
    }

    private static ManagerApplyDTO dto(String reason, List<FormDTOs.Answer> answers) {
        ManagerApplyDTO d = new ManagerApplyDTO();
        d.setReason(reason);
        d.setAnswers(answers);
        return d;
    }
}
