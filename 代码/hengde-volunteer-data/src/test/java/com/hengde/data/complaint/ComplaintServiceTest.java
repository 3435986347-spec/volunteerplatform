package com.hengde.data.complaint;

import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.AdminUser;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RecordingSmsConfig;
import com.hengde.common.testsupport.RecordingSmsConfig.RecordingSmsService;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.data.complaint.dto.ComplaintDTOs;
import com.hengde.data.complaint.service.ComplaintService;
import com.hengde.data.complaint.service.ComplaintService.Scope;
import com.hengde.data.complaint.support.ComplaintFlow;
import com.hengde.data.complaint.vo.ComplaintVOs;
import com.hengde.organization.form.dto.FormDTOs;
import com.hengde.organization.form.service.FormService;
import com.hengde.organization.form.support.FormFlow;
import com.hengde.organization.form.support.QuestionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投诉建议（V4 投诉建议批，Row 43）：默认进监察部 → 受理 → 流转 → 答复办结，处理进度对志愿者只露该露的；
 * 「工单在谁手上谁看」；答复发站内提示与短信；问卷场景接入；24 小时条数限制。
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = {"hengde.data.complaint.default-department=监察部",
        "hengde.data.complaint.daily-limit=3",
        "hengde.data.complaint.sms-reply-max-chars=10",
        "hengde.sms.templates.complaint-replied=T-COMPLAINT"})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, RecordingSmsConfig.class})
class ComplaintServiceTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);

    @Autowired
    private ComplaintService complaintService;
    @Autowired
    private FormService formService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private AdminUserMapper adminUserMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private RecordingSmsService sms;
    @Autowired
    private JdbcTemplate jdbc;

    private Scope supervisor;
    private Scope publicity;
    private Long volunteer;

    @BeforeEach
    void setUp() {
        sms.clear();
        jdbc.update("UPDATE org_form SET status = 2 WHERE scene = ? AND status = 1", FormFlow.SCENE_COMPLAINT);
        Long sup = admin("监察部");
        Long pub = admin("宣传部");
        supervisor = new Scope(sup, "监察部", false);
        publicity = new Scope(pub, "宣传部", false);
        volunteer = volunteer(true);
    }

    @Test
    void fullFlow_defaultDepartment_accept_transfer_reply_andWhatTheVolunteerSees() {
        Long id = complaintService.submit(volunteer, submit(ComplaintFlow.TYPE_COMPLAINT, "活动现场没有饮用水"));
        ComplaintVOs.Complaint c = complaintService.detailForAdmin(supervisor, id);
        assertEquals("监察部", c.getCurrentDepartment(), "Row 43「投诉默认到监察部」");
        assertEquals(ComplaintFlow.PENDING, c.getStatus());
        assertTrue(c.getComplaintNo().startsWith("TS"));
        assertMessage("工单不存在", () -> complaintService.detailForAdmin(publicity, id), "还没转过去，宣传部看不到");

        complaintService.accept(supervisor, id);
        assertMessage("只有待受理的工单可以受理（当前：处理中）", () -> complaintService.accept(supervisor, id));
        complaintService.note(supervisor, id, "这件事涉及物资采购，内部先核实");
        complaintService.transfer(supervisor, id, "宣传部", "活动由宣传部组织");

        assertMessage("工单不存在", () -> complaintService.accept(supervisor, id), "转走之后监察部（无全部门权限）就碰不到了");
        ComplaintVOs.Complaint atPub = complaintService.detailForAdmin(publicity, id);
        assertEquals(ComplaintFlow.PENDING, atPub.getStatus(), "流转后回到待受理");
        assertEquals(1, atPub.getTransferCount());

        complaintService.reply(publicity, id, "已在下次活动配备饮用水，感谢反馈");
        assertMessage("工单已经办结", () -> complaintService.reply(publicity, id, "再答复一次"));
        assertMessage("已办结的工单不能再流转", () -> complaintService.transfer(publicity, id, "监察部", null));

        ComplaintVOs.Complaint mine = complaintService.detailMine(id, volunteer);
        assertEquals(ComplaintFlow.CLOSED, mine.getStatus());
        assertEquals("宣传部", mine.getReplyDepartment());
        assertEquals(List.of("已提交，由监察部受理", "监察部已受理，正在处理", "已转交宣传部处理", "宣传部已答复"),
                mine.getProgress().stream().map(ComplaintVOs.Progress::getDescription).toList(),
                "内部备注不在志愿者的进度里");
        assertTrue(mine.getProgress().stream().filter(p -> p.getAction() == ComplaintFlow.ACT_TRANSFER)
                .allMatch(p -> p.getContent() == null), "流转理由是部门之间的话，不下发给志愿者");
        assertNull(mine.getVolunteerPhone());

        ComplaintVOs.Complaint forAdmin = complaintService.detailForAdmin(publicity, id);
        assertEquals(5, forAdmin.getProgress().size(), "后台看得到全部五步");
        assertTrue(forAdmin.getProgress().stream().anyMatch(p -> "活动由宣传部组织".equals(p.getContent())));
        assertTrue(forAdmin.getProgress().stream().anyMatch(p -> Boolean.FALSE.equals(p.getVisible())));
        assertNotNull(forAdmin.getVolunteerPhone(), "处理人要联系提交人");

        assertMessage("工单不存在", () -> complaintService.detailMine(id, volunteer(true)), "别人的与不存在同一句话");
    }

    @Test
    void reply_notifiesInAppAndBySms_withOnlyTheBeginningOfTheReply() {
        Long id = complaintService.submit(volunteer, submit(ComplaintFlow.TYPE_SUGGESTION, "建议增加周末的活动场次"));
        String no = complaintService.detailMine(id, volunteer).getComplaintNo();
        complaintService.reply(supervisor, id, "感谢建议，下个月起每周六加开一场，欢迎报名参加");

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-COMPLAINT");
        assertEquals(1, sent.size());
        assertEquals(no, sent.get(0).params().get("submissionNumber"));
        assertEquals("感谢建议，下个月起每…", sent.get(0).params().get("replyContent"), "短信只放开头（本用例配成 10 字）");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM volunteer_notification WHERE volunteer_id = ? "
                + "AND type = 5 AND biz_type = 3 AND biz_id = ?", Integer.class, volunteer, id), "站内提示一条");
    }

    @Test
    void scope_allDepartments_andTransferTargetsMustHaveActiveAccounts() {
        Long id = complaintService.submit(volunteer, submit(ComplaintFlow.TYPE_COMPLAINT, "负责人态度不好"));
        Scope all = new Scope(admin("理事会"), "理事会", true);
        assertEquals(id, complaintService.detailForAdmin(all, id).getId(), "全部门权限看得到别的部门的工单");
        assertTrue(complaintService.listForAdmin(all, page(), null, null, "监察部", null).getRecords().stream()
                .anyMatch(c -> c.getId().equals(id)));
        assertFalse(complaintService.listForAdmin(publicity, page(), null, null, "监察部", null).getRecords().stream()
                .anyMatch(c -> c.getId().equals(id)), "没有全部门权限时 department 参数无效，只看本部门");
        assertTrue(complaintService.listForAdmin(new Scope(admin(null), null, false), page(), null, null, null, null)
                .getRecords().isEmpty(), "没填部门的账号一条都看不到");

        String ghost = "不存在的部门" + SEQ.incrementAndGet();
        assertMessage("「" + ghost + "」没有启用的后台账号，转过去没人看得到",
                () -> complaintService.transfer(all, id, ghost, null));
        Long disabled = admin("停用部" + SEQ.incrementAndGet());
        jdbc.update("UPDATE admin_user SET status = 1 WHERE id = ?", disabled);
        String disabledDept = jdbc.queryForObject("SELECT department FROM admin_user WHERE id = ?", String.class, disabled);
        assertFalse(complaintService.departments().contains(disabledDept), "只有停用账号的部门不能作为流转目标");
        assertMessage("工单已经在「监察部」了", () -> complaintService.transfer(all, id, "监察部", null));

        complaintService.transfer(all, id, "宣传部", "代为转交");
        assertEquals("宣传部", complaintService.detailForAdmin(publicity, id).getCurrentDepartment());
    }

    @Test
    void submit_validatesImages_limitsPerDay_andRequiresAnActiveAccount() {
        ComplaintDTOs.Submit withExternal = submit(ComplaintFlow.TYPE_COMPLAINT, "有图为证");
        withExternal.setImages(List.of("https://evil.example.com/a.png"));
        assertMessage("图片无效，请重新上传", () -> complaintService.submit(volunteer, withExternal), "外链不收");
        ComplaintDTOs.Submit withOwn = submit(ComplaintFlow.TYPE_COMPLAINT, "有图为证");
        String own = "[oss-disabled]/form/20260917/" + "b".repeat(32) + ".png";
        withOwn.setImages(List.of(own));
        Long id = complaintService.submit(volunteer, withOwn);
        assertEquals(List.of(own), complaintService.detailMine(id, volunteer).getImages());

        assertMessage("类型只能是 1投诉 / 2建议", () -> complaintService.submit(volunteer, submit(9, "x")));
        complaintService.submit(volunteer, submit(ComplaintFlow.TYPE_SUGGESTION, "第二条"));
        complaintService.submit(volunteer, submit(ComplaintFlow.TYPE_SUGGESTION, "第三条"));
        assertMessage("24 小时内最多提交 3 条投诉建议，请稍后再试",
                () -> complaintService.submit(volunteer, submit(ComplaintFlow.TYPE_SUGGESTION, "第四条")));
        jdbc.update("UPDATE data_complaint SET create_time = ? WHERE volunteer_id = ?",
                LocalDateTime.now().minusHours(25), volunteer);
        complaintService.submit(volunteer, submit(ComplaintFlow.TYPE_SUGGESTION, "过了一天又能提"));

        Long guest = volunteer(false);
        assertNotNull(complaintService.submit(guest, submit(ComplaintFlow.TYPE_COMPLAINT, "游客也能提")),
                "投诉建议对所有登录账号开放");
        Long banned = volunteer(true);
        jdbc.update("UPDATE volunteer SET status = 1 WHERE id = ?", banned);
        assertMessage("账号状态异常，无法提交", () -> complaintService.submit(banned, submit(ComplaintFlow.TYPE_COMPLAINT, "x")));
    }

    @Test
    void complaintForm_whenPublished_answersAreRequiredAndShownToHandlers() {
        FormDTOs.Save form = new FormDTOs.Save();
        form.setScene(FormFlow.SCENE_COMPLAINT);
        form.setSingleSubmit(false);
        form.setTitle("投诉建议问卷-" + SEQ.incrementAndGet());
        FormDTOs.QuestionSave q = new FormDTOs.QuestionSave();
        q.setType(QuestionType.SINGLE);
        q.setTitle("涉及哪个方面");
        q.setOptions(List.of("活动组织", "服务态度", "小程序使用"));
        form.setQuestions(List.of(q));
        Long formId = formService.create(form, 1L);
        formService.publish(formId, 1L);
        Long questionId = formService.detail(formId).getQuestions().get(0).getId();

        assertMessage("第 1 题「涉及哪个方面」是必答题",
                () -> complaintService.submit(volunteer, submit(ComplaintFlow.TYPE_COMPLAINT, "没填问卷")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM data_complaint WHERE volunteer_id = ?",
                Integer.class, volunteer), "问卷没过，工单也不落");
        ComplaintDTOs.Submit ok = submit(ComplaintFlow.TYPE_COMPLAINT, "小程序卡顿");
        FormDTOs.Answer a = new FormDTOs.Answer();
        a.setQuestionId(questionId);
        a.setValue("C");
        ok.setAnswers(List.of(a));
        Long id = complaintService.submit(volunteer, ok);
        assertEquals("小程序使用", complaintService.detailForAdmin(supervisor, id).getFormAnswers().get(0).getDisplay());
        formService.close(formId);
    }

    // ---------------- helpers ----------------

    private Long admin(String department) {
        AdminUser a = new AdminUser();
        a.setUsername("complaint-admin-" + System.nanoTime() + "-" + SEQ.incrementAndGet());
        a.setPassword("x");
        a.setRealName("处理人" + SEQ.get());
        a.setDepartment(department);
        a.setIsSuperAdmin(0);
        a.setStatus(0);
        adminUserMapper.insert(a);
        return a.getId();
    }

    private Long volunteer(boolean registered) {
        Volunteer v = new Volunteer();
        v.setOpenid("test:complaint:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        String phone = String.format("139%08d", SEQ.incrementAndGet() % 100_000_000L);
        v.setPhone(cryptoUtil.encrypt(phone));
        v.setPhoneHash(cryptoUtil.hashPhone(phone));
        v.setRealName(registered ? "投诉人" + SEQ.get() : null);
        v.setStatus(0);
        v.setManagerFlag(0);
        if (registered) {
            v.setRegisterTime(LocalDateTime.now());
        }
        volunteerMapper.insert(v);
        return v.getId();
    }

    private static ComplaintDTOs.Submit submit(int type, String content) {
        ComplaintDTOs.Submit d = new ComplaintDTOs.Submit();
        d.setType(type);
        d.setContent(content);
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
