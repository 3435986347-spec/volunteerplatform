package com.hengde.activity;

import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.dao.ActivityViolationMapper;
import com.hengde.activity.dto.ProxyEnrollDTO;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.ActivityEnrollment;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.entity.ActivityViolation;
import com.hengde.activity.service.ActivityReminderService;
import com.hengde.activity.service.ActivityService;
import com.hengde.activity.service.AttendanceService;
import com.hengde.activity.service.EnrollmentAdminService;
import com.hengde.activity.service.EnrollmentService;
import com.hengde.activity.service.ServiceRecordService;
import com.hengde.activity.service.ViolationReviewService;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.testsupport.RecordingSmsConfig;
import com.hengde.common.testsupport.RecordingSmsConfig.RecordingSmsService;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.VolunteerGroupMapper;
import com.hengde.organization.biz.dao.VolunteerGroupMemberMapper;
import com.hengde.organization.biz.entity.VolunteerGroup;
import com.hengde.organization.biz.entity.VolunteerGroupMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * activity 域 7 个通知短信落点的接线验证（协会 2026-08-11 报备的通知类模板）。
 *
 * <p><b>为什么必须逐条断言短信内容而不只断言业务成功</b>：这些通知在业务侧一律
 * 「try 住 + 记日志」，发不出去不会让审核失败。也就是说参数拼错、模板选错、发错人，
 * 线上都只留一行 ERROR，业务照常返回成功——用例是唯一能发现它的地方。</p>
 *
 * <p>模板 ID 由 {@code @SpringBootTest(properties=...)} 就地配成 {@code T-*} 假值，
 * 断言按模板 ID 分桶，因此「发了哪一条模板」本身也是被钉住的。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = {
        "hengde.sms.templates.enrollment-approved=T-ENROLL-OK",
        "hengde.sms.templates.enrollment-rejected=T-ENROLL-NO",
        "hengde.sms.templates.enrollment-position=T-PROXY",
        "hengde.sms.templates.activity-start-reminder=T-REMIND",
        "hengde.sms.templates.activity-cancelled=T-CANCEL",
        "hengde.sms.templates.activity-comment-reminder=T-COMMENT",
        "hengde.sms.templates.service-record-credited=T-POINTS",
        "hengde.sms.templates.activity-violation=T-VIOLATION",
        // 提醒任务：提前量放大到 240h，好让用例造的「3 天后开始」的场次落进窗口
        "hengde.activity.reminder.lead-hours=240"
})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, RecordingSmsConfig.class})
class ActivitySmsNotifyTest {

    @Autowired
    private RecordingSmsService sms;
    @Autowired
    private EnrollmentAdminService enrollmentAdminService;
    @Autowired
    private EnrollmentService enrollmentService;
    @Autowired
    private ActivityService activityService;
    @Autowired
    private AttendanceService attendanceService;
    @Autowired
    private ServiceRecordService serviceRecordService;
    @Autowired
    private ViolationReviewService violationReviewService;
    @Autowired
    private ActivityReminderService activityReminderService;

    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ActivitySlotMapper slotMapper;
    @Autowired
    private ActivityEnrollmentMapper enrollmentMapper;
    @Autowired
    private ActivityAttendanceMapper attendanceMapper;
    @Autowired
    private ActivityViolationMapper violationMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private VolunteerGroupMapper groupMapper;
    @Autowired
    private VolunteerGroupMemberMapper groupMemberMapper;
    @Autowired
    private CryptoUtil cryptoUtil;

    @BeforeEach
    void reset() {
        sms.clear();
    }

    // ---------- 报名审核 ----------

    @Test
    void enrollmentApproved_sendsSlotStartTimeNotActivityStartTime() {
        LocalDateTime activityStart = LocalDateTime.now().plusDays(3).withHour(9).withMinute(0).withSecond(0).withNano(0);
        Long aid = insertActivity("植树活动", activityStart, activityStart.plusHours(9), "西湖公园");
        // 下午场：报了这一场的人不该收到写着 09:00 的短信
        Long slotId = insertSlot(aid, activityStart.withHour(14), activityStart.withHour(17));
        Long vid = insertVolunteer("13911110001");
        Long eid = insertEnrollment(aid, slotId, vid, 0);

        enrollmentAdminService.approve(eid, 1L);

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-ENROLL-OK");
        assertEquals(1, sent.size());
        assertEquals("植树活动", sent.get(0).params().get("activityName"));
        assertTrue(sent.get(0).params().get("startTime").endsWith("14:00"),
                "时间必须取本场次，报了下午场的人收到 09:00 会白跑一趟；实际=" + sent.get(0).params().get("startTime"));
        assertEquals("西湖公园", sent.get(0).params().get("location"));
    }

    @Test
    void enrollmentRejected_carriesReason() {
        Long aid = insertActivity("清扫活动", LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(2).plusHours(3), "海边");
        Long slotId = insertSlot(aid, LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(2).plusHours(3));
        Long vid = insertVolunteer("13911110002");
        Long eid = insertEnrollment(aid, slotId, vid, 0);

        enrollmentAdminService.reject(eid, "年龄不符合要求", 1L);

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-ENROLL-NO");
        assertEquals(1, sent.size());
        assertEquals("年龄不符合要求", sent.get(0).params().get("reason"));
        assertTrue(sms.byTemplateId("T-ENROLL-OK").isEmpty(), "拒绝不该发通过的那条模板");
    }

    @Test
    void selfEnroll_sendsNothing_butManualEnrollByAdminDoes() {
        LocalDateTime start = LocalDateTime.now().plusDays(6).withNano(0);
        Long aid = insertActivity("社区宣讲", start, start.plusHours(2), "党群中心");
        setNeedAudit(aid, 0);   // 免审：自助报名直接落已通过，最容易误发的就是这一支
        Long slotId = insertSlot(aid, start, start.plusHours(2));
        Long selfer = insertVolunteer("13911110081");
        Long added = insertVolunteer("13911110082");

        enrollmentService.enroll(aid, List.of(slotId), selfer);
        assertTrue(sms.all().isEmpty(), "自己刚点完的事不必再花一条短信告诉他");

        enrollmentService.manualEnroll(aid, added, List.of(slotId), 1L);

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-ENROLL-OK");
        assertEquals(1, sent.size(), "后台补录是别人替他做的决定，他事先并不知情");
        assertEquals("13911110082", sent.get(0).phone());
        assertEquals("社区宣讲", sent.get(0).params().get("activityName"));
    }

    // ---------- 活动取消 ----------

    @Test
    void activityCancelled_notifiesActiveEnrolleesOnceEach() {
        LocalDateTime start = LocalDateTime.now().plusDays(4);
        Long aid = insertActivity("助老活动", start, start.plusHours(4), "敬老院");
        Long slot1 = insertSlot(aid, start, start.plusHours(2));
        Long slot2 = insertSlot(aid, start.plusHours(2), start.plusHours(4));

        Long pending = insertVolunteer("13911110011");
        Long approved = insertVolunteer("13911110012");
        Long cancelled = insertVolunteer("13911110013");
        // 同一人报两场：两行报名，但只该收到一条短信
        insertEnrollment(aid, slot1, approved, 1);
        insertEnrollment(aid, slot2, approved, 1);
        insertEnrollment(aid, slot1, pending, 0);
        insertEnrollment(aid, slot1, cancelled, 3);

        activityService.cancel(aid, "场地临时不可用");

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-CANCEL");
        Set<String> phones = sent.stream().map(RecordingSmsService.Sent::phone).collect(Collectors.toSet());
        assertEquals(Set.of("13911110011", "13911110012"), phones,
                "待审核与已通过都要通知；已取消报名的人不该收到");
        assertEquals(2, sent.size(), "报了两个场次的人只该收到一条，不是每行一条");
        assertEquals("场地临时不可用", sent.get(0).params().get("reason"));
    }

    @Test
    void activityCancelled_withoutReason_fallsBackToGuidanceText() {
        LocalDateTime start = LocalDateTime.now().plusDays(4);
        Long aid = insertActivity("义诊活动", start, start.plusHours(3), "社区中心");
        Long slotId = insertSlot(aid, start, start.plusHours(3));
        insertEnrollment(aid, slotId, insertVolunteer("13911110014"), 1);

        activityService.cancel(aid, null);

        // 空原因不能变成「原因：」后面什么都没有——那种短信只会招来一堆电话
        assertEquals("详情请咨询活动联系人", sms.byTemplateId("T-CANCEL").get(0).params().get("reason"));
    }

    // ---------- 活动结束邀评 ----------

    @Test
    void finishActivity_onlyNotifiesThoseWhoActuallyCheckedIn() {
        LocalDateTime start = LocalDateTime.now().minusHours(3);
        Long aid = insertActivity("图书整理", start, start.plusHours(2), "图书馆");
        Long slotId = insertSlot(aid, start, start.plusHours(2));
        Long attended = insertVolunteer("13911110021");
        Long noShow = insertVolunteer("13911110022");
        insertAttendance(aid, slotId, attended, start.plusMinutes(5));
        insertAttendance(aid, slotId, noShow, null);   // 有考勤行但没签到（负责人补建的）
        setRunStatus(aid, 1);

        attendanceService.finishActivity(aid, 1L);

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-COMMENT");
        assertEquals(1, sent.size(), "没到场的人收到「欢迎评价」只会莫名其妙，而且他也确实评不了");
        assertEquals("13911110021", sent.get(0).phone());
        assertEquals("图书整理", sent.get(0).params().get("activityName"));
    }

    // ---------- 积分发放 ----------

    @Test
    void grantPoints_reportsHoursWithOneDecimal() {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        Long aid = insertActivity("敬老陪伴", start, start.plusHours(2), "敬老院");
        Long slotId = insertSlot(aid, start, start.plusHours(2));
        Long vid = insertVolunteer("13911110031");
        Long attId = insertConfirmedAttendance(aid, slotId, vid, 90);   // 90 分钟 = 1.5 小时

        int award = serviceRecordService.grantPoints(attId, 0, 1L);

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-POINTS");
        assertEquals(1, sent.size());
        assertEquals("1.5", sent.get(0).params().get("hours"),
                "整数会把 90 分钟说成 1 小时或 2 小时，两个都不对");
        assertEquals(String.valueOf(award), sent.get(0).params().get("points"));
    }

    // ---------- 违规审核 ----------

    @Test
    void violationApproved_sendsChineseTypeLabel_rejectedSendsNothing() {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        Long aid = insertActivity("交通劝导", start, start.plusHours(2), "路口");
        Long slotId = insertSlot(aid, start, start.plusHours(2));
        Long vid = insertVolunteer("13911110041");
        Long okId = insertViolation(aid, slotId, vid, 1);     // 1 = 玩手机
        Long rejectedId = insertViolation(aid, slotId, insertVolunteer("13911110042"), 3);

        violationReviewService.approve(okId, 1L);
        violationReviewService.reject(rejectedId, "证据不足", 1L);

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-VIOLATION");
        assertEquals(1, sent.size(), "驳回的那条志愿者仍然看不到，自然也不该收到短信");
        assertEquals("玩手机", sent.get(0).params().get("violationType"), "短信里出现「违规类型：1」比不发还糟");
        assertEquals("0", sent.get(0).params().get("pointsDeducted"), "现场违规本身不扣分，扣分是处罚单干的");
    }

    // ---------- 代报名 ----------

    @Test
    void proxyEnroll_notifiesTargetWhoDidNotActThemselves() {
        LocalDateTime start = LocalDateTime.now().plusDays(5);
        Long aid = insertActivity("河道清洁", start, start.plusHours(3), "南渡河");
        Long slotId = insertSlot(aid, start, start.plusHours(3));
        Long actor = insertVolunteer("13911110051");
        Long target = insertVolunteer("13911110052");
        insertGroupWithMembers(actor, List.of(target));

        ProxyEnrollDTO dto = new ProxyEnrollDTO();
        dto.setVolunteerIds(List.of(target));
        dto.setSlotIds(List.of(slotId));
        enrollmentService.proxyEnroll(aid, dto, actor);

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-PROXY");
        assertEquals(1, sent.size(), "被代报名的人事先并不知情，不通知就可能到了当天才发现");
        assertEquals("13911110052", sent.get(0).phone());
        assertEquals("河道清洁", sent.get(0).params().get("Activity"),
                "这条模板的变量名首字母大写，与其余模板不一致——传错不会报错，只会把那一段替换成空");
        assertTrue(sent.get(0).params().get("message").contains("代为报名"));
    }

    // ---------- 活动开始提醒（定时任务） ----------

    @Test
    void startReminder_sendsOnceThenNeverAgain() {
        LocalDateTime start = LocalDateTime.now().plusDays(3).withNano(0);
        Long aid = insertActivity("赛事引导", start, start.plusHours(4), "体育馆");
        Long slotId = insertSlot(aid, start, start.plusHours(4));
        Long approved = insertVolunteer("13911110061");
        Long pending = insertVolunteer("13911110062");
        insertEnrollment(aid, slotId, approved, 1);
        insertEnrollment(aid, slotId, pending, 0);

        activityReminderService.sendDueReminders();

        // ⚠️ 【断言只认本用例自己的号码】提醒任务是<b>全库扫描</b>，本类其它用例造的报名同样会被扫到。
        //    早先这里写的是「发出的短信恰好等于这一个号码」，在单跑时绿、全量跑时红——
        //    与排行榜用例踩过的是同一件事（全库聚合的用例之间没有天然隔离）。
        List<RecordingSmsService.Sent> mine = sms.byTemplateId("T-REMIND").stream()
                .filter(x -> "赛事引导".equals(x.params().get("activityName")))
                .toList();
        assertEquals(1, mine.size(), "该场次的已通过报名者应收到提醒");
        assertEquals("13911110061", mine.get(0).phone(),
                "待审核的人还不知道去不去得成，让他「准时签到」是错的指示");
        assertEquals("体育馆", mine.get(0).params().get("location"));

        sms.clear();
        activityReminderService.sendDueReminders();
        assertTrue(sms.byTemplateId("T-REMIND").stream()
                        .noneMatch(x -> "赛事引导".equals(x.params().get("activityName"))),
                "同一场提醒发两遍会让人以为活动改期了；第二轮不该再扫到它");
        // ⚠️ 本断言证明的是【扫描条件】把已提醒的场次排除在外（reminder_sent_time IS NULL），
        //    不是那条 UPDATE 上的 CAS——已变异验证：去掉 CAS 的 isNull 条件，本用例照样绿。
        //    CAS 由 startReminder_casLosesWhenAnotherScannerAlreadyMarked 单独覆盖。
    }

    @Test
    void startReminder_skipsCancelledActivityWithoutBurningTheFlag() {
        LocalDateTime start = LocalDateTime.now().plusDays(3).withNano(0);
        Long aid = insertActivity("待定活动", start, start.plusHours(2), "待定");
        Long slotId = insertSlot(aid, start, start.plusHours(2));
        insertEnrollment(aid, slotId, insertVolunteer("13911110071"), 1);
        setStatus(aid, 0);   // 草稿：尚未上线

        activityReminderService.sendDueReminders();
        assertTrue(remindersFor("待定活动").isEmpty(), "没上线的活动不该发提醒");

        // 关键：跳过时不能把「已提醒」标记打上，否则活动开始前才通过审核时，这一场就永久收不到提醒
        setStatus(aid, 1);
        activityReminderService.sendDueReminders();
        assertEquals(1, remindersFor("待定活动").size(), "上线之后仍应补发这一场的提醒");
    }

    /** 只取某个活动的提醒短信——提醒任务全库扫描，不过滤就会把别的用例造的数据算进来。 */
    private List<RecordingSmsService.Sent> remindersFor(String activityName) {
        return sms.byTemplateId("T-REMIND").stream()
                .filter(x -> activityName.equals(x.params().get("activityName")))
                .toList();
    }

    @Test
    void startReminder_noApprovedEnrollmentYet_doesNotBurnTheFlag() {
        LocalDateTime start = LocalDateTime.now().plusDays(3).withNano(0);
        Long aid = insertActivity("需审核活动", start, start.plusHours(3), "文化广场");
        Long slotId = insertSlot(aid, start, start.plusHours(3));
        Long late = insertVolunteer("13911110091");
        Long eid = insertEnrollment(aid, slotId, late, 0);   // 扫到的那一刻还是待审核

        activityReminderService.sendDueReminders();
        assertTrue(remindersFor("需审核活动").isEmpty(), "全是待审核，这一轮不该发");

        // 管理员随后批了人——这一场必须还能被扫到。
        // 若上一轮已经把「已提醒」标记打上，扫描条件 reminder_sent_time IS NULL 会把它永久排除，
        // 这个人就再也收不到提醒了。need_audit=1 且 24 小时内审批在本项目是常态，不是边角情形。
        enrollmentAdminService.approve(eid, 1L);
        sms.clear();
        activityReminderService.sendDueReminders();

        List<RecordingSmsService.Sent> mine = remindersFor("需审核活动");
        assertEquals(1, mine.size(), "审批之后的下一轮必须补上这一场的提醒");
        assertEquals("13911110091", mine.get(0).phone());
    }

    @Test
    void startReminder_casLosesWhenAnotherScannerAlreadyMarked() {
        LocalDateTime start = LocalDateTime.now().plusDays(3).withNano(0);
        Long aid = insertActivity("并发提醒活动", start, start.plusHours(2), "会展中心");
        Long slotId = insertSlot(aid, start, start.plusHours(2));
        insertEnrollment(aid, slotId, insertVolunteer("13911110092"), 1);

        // 第一个扫描方正常跑完：标记落库、短信发出
        activityReminderService.sendDueReminders();
        assertEquals(1, remindersFor("并发提醒活动").size());

        // 第二个扫描方：它在对方 update 之前就把这一行 select 出来了，手里那个 slot 对象仍是「未提醒」。
        // 这正是多实例部署下会发生的时序，用一个过期对象就能确定性重现，不必靠线程与 sleep。
        ActivitySlot stale = new ActivitySlot();
        stale.setId(slotId);
        stale.setActivityId(aid);
        stale.setStartTime(start);
        sms.clear();

        boolean sent = activityReminderService.remindOne(stale, LocalDateTime.now());

        assertFalse(sent, "CAS 必须落空——标记已被对方占住");
        assertTrue(remindersFor("并发提醒活动").isEmpty(),
                "同一场提醒发两遍会让人以为活动改期了；挡住它的就是那句 isNull(reminderSentTime)");
    }

    // ---------- 造数 ----------

    private Long insertActivity(String title, LocalDateTime start, LocalDateTime end, String location) {
        Activity a = new Activity();
        a.setTitle(title);
        a.setLocation(location);
        a.setStartTime(start);
        a.setEndTime(end);
        a.setStatus(1);
        a.setRunStatus(0);
        a.setPointsBase(10);
        a.setNeedAudit(1);
        a.setRequireMinJoinCount(0);
        activityMapper.insert(a);
        a.setSerialNo(a.getId());
        activityMapper.updateById(a);
        return a.getId();
    }

    private void setNeedAudit(Long activityId, int needAudit) {
        Activity a = activityMapper.selectById(activityId);
        a.setNeedAudit(needAudit);
        activityMapper.updateById(a);
    }

    private void setStatus(Long activityId, int status) {
        Activity a = activityMapper.selectById(activityId);
        a.setStatus(status);
        activityMapper.updateById(a);
    }

    private void setRunStatus(Long activityId, int runStatus) {
        Activity a = activityMapper.selectById(activityId);
        a.setRunStatus(runStatus);
        activityMapper.updateById(a);
    }

    private Long insertSlot(Long activityId, LocalDateTime start, LocalDateTime end) {
        ActivitySlot slot = new ActivitySlot();
        slot.setActivityId(activityId);
        slot.setProjectName("岗位_" + System.nanoTime());
        slot.setStartTime(start);
        slot.setEndTime(end);
        slot.setNeedCount(10);
        slotMapper.insert(slot);
        return slot.getId();
    }

    private Long insertVolunteer(String phonePlain) {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime());
        v.setRealName("志愿者_" + System.nanoTime());
        v.setPhone(cryptoUtil.encrypt(phonePlain));
        v.setPhoneHash(cryptoUtil.hashPhone(phonePlain));
        v.setBirthday(LocalDate.now().minusYears(20));
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private Long insertEnrollment(Long activityId, Long slotId, Long volunteerId, int status) {
        ActivityEnrollment e = new ActivityEnrollment();
        e.setActivityId(activityId);
        e.setSlotId(slotId);
        e.setVolunteerId(volunteerId);
        e.setStatus(status);
        e.setEnrollTime(LocalDateTime.now());
        enrollmentMapper.insert(e);
        return e.getId();
    }

    private void insertAttendance(Long activityId, Long slotId, Long volunteerId, LocalDateTime checkIn) {
        ActivityAttendance att = new ActivityAttendance();
        att.setActivityId(activityId);
        att.setSlotId(slotId);
        att.setVolunteerId(volunteerId);
        att.setCheckInTime(checkIn);
        att.setAttendStatus(1);
        att.setSecretaryStatus(0);
        att.setPointsStatus(0);
        attendanceMapper.insert(att);
    }

    private Long insertConfirmedAttendance(Long activityId, Long slotId, Long volunteerId, int minutes) {
        ActivityAttendance att = new ActivityAttendance();
        att.setActivityId(activityId);
        att.setSlotId(slotId);
        att.setVolunteerId(volunteerId);
        att.setCheckInTime(LocalDateTime.now().minusHours(2));
        att.setCheckOutTime(LocalDateTime.now().minusMinutes(30));
        att.setAttendStatus(1);
        att.setServiceMinutes(minutes);
        att.setSecretaryStatus(1);
        att.setSecretaryTime(LocalDateTime.now());
        att.setPointsStatus(0);
        attendanceMapper.insert(att);
        return att.getId();
    }

    private Long insertViolation(Long activityId, Long slotId, Long volunteerId, int type) {
        ActivityViolation v = new ActivityViolation();
        v.setActivityId(activityId);
        v.setSlotId(slotId);
        v.setVolunteerId(volunteerId);
        v.setViolationType(type);
        v.setDescription("用例造的违规记录");
        v.setRecordedBy(1L);
        v.setRecordedTime(LocalDateTime.now());
        v.setReviewStatus(ActivityViolation.REVIEW_PENDING);
        violationMapper.insert(v);
        return v.getId();
    }

    private void insertGroupWithMembers(Long leaderId, List<Long> memberIds) {
        VolunteerGroup g = new VolunteerGroup();
        g.setGroupNo("G_sms_" + System.nanoTime());
        g.setName("短信用例小组_" + System.nanoTime());
        g.setLeaderId(leaderId);
        g.setStatus(1);
        groupMapper.insert(g);
        insertMember(g.getId(), leaderId, 1);
        for (Long mid : memberIds) {
            insertMember(g.getId(), mid, 0);
        }
    }

    private void insertMember(Long groupId, Long volunteerId, int role) {
        VolunteerGroupMember m = new VolunteerGroupMember();
        m.setGroupId(groupId);
        m.setVolunteerId(volunteerId);
        m.setRole(role);
        m.setStatus(1);
        m.setApplyTime(LocalDateTime.now());
        m.setAuditTime(LocalDateTime.now());
        groupMemberMapper.insert(m);
    }
}
