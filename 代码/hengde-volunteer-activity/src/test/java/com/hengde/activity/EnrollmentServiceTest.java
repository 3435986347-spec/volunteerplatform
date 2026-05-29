package com.hengde.activity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.dto.ProxyEnrollDTO;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.ActivityEnrollment;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.service.EnrollmentService;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.constant.Gender;
import com.hengde.common.constant.Grade;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.VolunteerGroupMapper;
import com.hengde.organization.biz.dao.VolunteerGroupMemberMapper;
import com.hengde.organization.biz.entity.VolunteerGroup;
import com.hengde.organization.biz.entity.VolunteerGroupMember;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报名/取消校验链与并发锁的集成验证。MySQL + Redis 均由 Testcontainers 起。<b>需本机有 Docker。</b>
 *
 * <p>每个用例用独立的志愿者（唯一 openid）+ 独立活动隔离数据，避免同容器跨方法污染。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class EnrollmentServiceTest {

    private static final LocalDateTime A_START = LocalDateTime.now().plusDays(7).withNano(0);

    @Autowired
    private EnrollmentService enrollmentService;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ActivitySlotMapper activitySlotMapper;
    @Autowired
    private ActivityEnrollmentMapper enrollmentMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private ActivityAttendanceMapper attendanceMapper;
    @Autowired
    private VolunteerGroupMapper groupMapper;
    @Autowired
    private VolunteerGroupMemberMapper groupMemberMapper;

    @Test
    void enroll_noAudit_becomesApproved() {
        Long vid = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> a.setNeedAudit(0));
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));

        int n = enrollmentService.enroll(aid, List.of(slot), vid);

        assertEquals(1, n);
        assertEquals(1, latestEnrollment(aid, vid).getStatus(), "need_audit=0 报名即通过");
    }

    @Test
    void enroll_needAudit_becomesPending() {
        Long vid = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> a.setNeedAudit(1));
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));

        enrollmentService.enroll(aid, List.of(slot), vid);

        assertEquals(0, latestEnrollment(aid, vid).getStatus(), "need_audit=1 落库为待审核");
    }

    @Test
    void enroll_duplicateActive_rejected() {
        Long vid = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> a.setNeedAudit(0));
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));
        enrollmentService.enroll(aid, List.of(slot), vid);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enrollmentService.enroll(aid, List.of(slot), vid));
        assertEquals("您已报名该活动", ex.getMessage());
    }

    @Test
    void enroll_afterCancel_allowed() {
        Long vid = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> a.setNeedAudit(0));
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));
        enrollmentService.enroll(aid, List.of(slot), vid);

        int cancelled = enrollmentService.cancel(aid, vid);
        assertEquals(1, cancelled);

        int n = enrollmentService.enroll(aid, List.of(slot), vid);
        assertEquals(1, n, "取消后应可再次报名");
    }

    @Test
    void enroll_belowMinProjects_rejected() {
        Long vid = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> {
            a.setNeedAudit(0);
            a.setMinProjects(2);
        });
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enrollmentService.enroll(aid, List.of(slot), vid));
        assertTrue(ex.getMessage().contains("至少需报名"));
    }

    @Test
    void enroll_aboveMaxProjects_rejected() {
        Long vid = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> {
            a.setNeedAudit(0);
            a.setMaxProjects(1);
        });
        Long s1 = insertSlot(aid, A_START, A_START.plusHours(2));
        Long s2 = insertSlot(aid, A_START.plusHours(3), A_START.plusHours(5));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enrollmentService.enroll(aid, List.of(s1, s2), vid));
        assertTrue(ex.getMessage().contains("最多可报名"));
    }

    @Test
    void eligibility_genderMismatch_rejected() {
        Long vid = insertVolunteer(Gender.FEMALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> {
            a.setNeedAudit(0);
            a.setRequireGender(1); // 仅限男
        });
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));

        assertThrows(BusinessException.class, () -> enrollmentService.enroll(aid, List.of(slot), vid));
    }

    @Test
    void eligibility_ageBelowMin_rejected() {
        Long vid = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(15), Grade.GRADE_9);
        Long aid = insertActivity(a -> {
            a.setNeedAudit(0);
            a.setRequireMinAge(18);
        });
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enrollmentService.enroll(aid, List.of(slot), vid));
        assertTrue(ex.getMessage().contains("年龄"));
    }

    @Test
    void eligibility_minJoinCount_rejected() {
        Long vid = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> {
            a.setNeedAudit(0);
            a.setRequireMinJoinCount(3);
        });
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enrollmentService.enroll(aid, List.of(slot), vid));
        assertTrue(ex.getMessage().contains("已参加活动次数不足"));
    }

    @Test
    void minJoinCount_countsDistinctActivities_notRows() {
        // 历史：在「同一个」活动里有 2 条已通过报名（2 个时间段）→ 应算 1 场，不是 2 场
        Long vid = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long hist = insertActivity(a -> a.setNeedAudit(0));
        Long h1 = insertSlot(hist, A_START.plusHours(4), A_START.plusHours(5));
        Long h2 = insertSlot(hist, A_START.plusHours(6), A_START.plusHours(7));
        insertApprovedEnrollment(hist, h1, vid);
        insertApprovedEnrollment(hist, h2, vid);

        Long aid = insertActivity(a -> {
            a.setNeedAudit(0);
            a.setRequireMinJoinCount(2);
        });
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enrollmentService.enroll(aid, List.of(slot), vid),
                "同一活动的多个时间段只算 1 场，不应满足 ≥2 场门槛");
        assertTrue(ex.getMessage().contains("已参加活动次数不足"));
    }

    @Test
    void minJoinCount_twoDistinctActivities_passes() {
        // 历史：2 个「不同」活动各 1 条已通过报名 → 算 2 场，满足 ≥2 门槛
        Long vid = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long hist1 = insertActivity(a -> a.setNeedAudit(0));
        insertApprovedEnrollment(hist1, insertSlot(hist1, A_START.plusHours(4), A_START.plusHours(5)), vid);
        Long hist2 = insertActivity(a -> a.setNeedAudit(0));
        insertApprovedEnrollment(hist2, insertSlot(hist2, A_START.plusHours(6), A_START.plusHours(7)), vid);

        Long aid = insertActivity(a -> {
            a.setNeedAudit(0);
            a.setRequireMinJoinCount(2);
        });
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));

        assertEquals(1, enrollmentService.enroll(aid, List.of(slot), vid), "已参加 2 个不同活动应满足门槛");
    }

    @Test
    void eligibility_minJoinMinutes_rejected() {
        Long vid = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> {
            a.setNeedAudit(0);
            a.setRequireMinJoinMinutes(120);   // 需累计 ≥120 分钟已确认服务时长
        });
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enrollmentService.enroll(aid, List.of(slot), vid));
        assertTrue(ex.getMessage().contains("已参加服务时长不足"));
    }

    @Test
    void eligibility_minJoinMinutes_passesWhenEnough() {
        Long vid = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        insertConfirmedAttendance(vid, 120);   // 历史已确认 120 分钟
        Long aid = insertActivity(a -> {
            a.setNeedAudit(0);
            a.setRequireMinJoinMinutes(120);
        });
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));

        assertEquals(1, enrollmentService.enroll(aid, List.of(slot), vid), "已确认时长达标应通过");
    }

    @Test
    void enroll_beforeOpenVolunteer_rejected() {
        Long vid = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> {
            a.setNeedAudit(0);
            a.setEnrollOpenVolunteer(LocalDateTime.now().plusHours(1));   // 1h 后才开放
        });
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enrollmentService.enroll(aid, List.of(slot), vid));
        assertEquals("尚未开放报名", ex.getMessage());
    }

    @Test
    void enroll_pastEnrollDeadline_rejected() {
        Long vid = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> {
            a.setNeedAudit(0);
            a.setEnrollDeadline(LocalDateTime.now().minusHours(1));
        });
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enrollmentService.enroll(aid, List.of(slot), vid));
        assertEquals("报名已截止", ex.getMessage());
    }

    @Test
    void cancel_pastCancelDeadline_rejected() {
        Long vid = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        // 先报名（取消截止在未来），再把活动的取消截止改到过去，模拟过期
        Long aid = insertActivity(a -> a.setNeedAudit(0));
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));
        enrollmentService.enroll(aid, List.of(slot), vid);

        Activity a = activityMapper.selectById(aid);
        a.setCancelDeadline(LocalDateTime.now().minusHours(1));
        activityMapper.updateById(a);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enrollmentService.cancel(aid, vid));
        assertEquals("已过取消报名截止时间", ex.getMessage());
    }

    @Test
    void cancel_finishedActivity_rejected() {
        Long vid = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> a.setNeedAudit(0));
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));
        enrollmentService.enroll(aid, List.of(slot), vid);

        // 活动结束后（cancelDeadline 为空）也不应允许取消
        Activity a = activityMapper.selectById(aid);
        a.setStatus(2);
        activityMapper.updateById(a);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enrollmentService.cancel(aid, vid));
        assertEquals("活动已结束或已取消，无法取消报名", ex.getMessage());
    }

    @Test
    void cancel_notEnrolled_rejected() {
        Long vid = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> a.setNeedAudit(0));
        insertSlot(aid, A_START, A_START.plusHours(2));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enrollmentService.cancel(aid, vid));
        assertEquals("您未报名该活动", ex.getMessage());
    }

    @Test
    void timeConflict_acrossActivities_rejected() {
        Long vid = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        // 活动1：报名占用 A_START ~ +2h
        Long a1 = insertActivity(a -> a.setNeedAudit(0));
        Long s1 = insertSlot(a1, A_START, A_START.plusHours(2));
        enrollmentService.enroll(a1, List.of(s1), vid);

        // 活动2：时间段与活动1重叠（+1h ~ +3h）
        Long a2 = insertActivity(a -> a.setNeedAudit(0));
        Long s2 = insertSlot(a2, A_START.plusHours(1), A_START.plusHours(3));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enrollmentService.enroll(a2, List.of(s2), vid));
        assertTrue(ex.getMessage().contains("时间冲突"));
    }

    // ---------- 代报名 ----------

    @Test
    void proxyEnroll_sameGroup_writesProxyBy() {
        Long actor = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(22), Grade.COLLEGE_1);
        Long t1 = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(22), Grade.COLLEGE_1);
        Long t2 = insertVolunteer(Gender.FEMALE, LocalDate.now().minusYears(22), Grade.COLLEGE_1);
        Long groupId = insertGroupWithMembers(actor, List.of(t1, t2));

        Long aid = insertActivity(a -> a.setNeedAudit(0));
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));

        ProxyEnrollDTO dto = new ProxyEnrollDTO();
        dto.setVolunteerIds(List.of(t1, t2));
        dto.setSlotIds(List.of(slot));

        int n = enrollmentService.proxyEnroll(aid, dto, actor);
        assertEquals(2, n, "两个 target × 1 slot");

        // 落库 proxy_by_volunteer_id = actor
        ActivityEnrollment e1 = latestEnrollment(aid, t1);
        ActivityEnrollment e2 = latestEnrollment(aid, t2);
        assertEquals(actor, e1.getProxyByVolunteerId());
        assertEquals(actor, e2.getProxyByVolunteerId());
        assertEquals(Integer.valueOf(1), e1.getStatus(), "needAudit=0 → 直接通过");
        // 群 id 仅用于断言 setup 成功，避免编译告警未使用
        assertTrue(groupId > 0);
    }

    @Test
    void proxyEnroll_targetNotInGroup_rejectedRollsBackAll() {
        Long actor = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(22), Grade.COLLEGE_1);
        Long inGroup = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(22), Grade.COLLEGE_1);
        Long stranger = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(22), Grade.COLLEGE_1);
        insertGroupWithMembers(actor, List.of(inGroup));   // stranger 不在组

        Long aid = insertActivity(a -> a.setNeedAudit(0));
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));

        ProxyEnrollDTO dto = new ProxyEnrollDTO();
        dto.setVolunteerIds(List.of(inGroup, stranger));
        dto.setSlotIds(List.of(slot));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enrollmentService.proxyEnroll(aid, dto, actor));
        assertTrue(ex.getMessage().contains("同一小组"));

        // 整批回滚：组内成员也没成功落库
        Long count = enrollmentMapper.selectCount(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, aid)
                .eq(ActivityEnrollment::getVolunteerId, inGroup));
        assertEquals(0L, count, "任一 target 校验失败应整批回滚");
    }

    @Test
    void proxyEnroll_actorWithoutGroup_rejected() {
        Long actor = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(22), Grade.COLLEGE_1);
        Long target = insertVolunteer(Gender.MALE, LocalDate.now().minusYears(22), Grade.COLLEGE_1);
        // actor 不属于任何小组

        Long aid = insertActivity(a -> a.setNeedAudit(0));
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));

        ProxyEnrollDTO dto = new ProxyEnrollDTO();
        dto.setVolunteerIds(List.of(target));
        dto.setSlotIds(List.of(slot));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enrollmentService.proxyEnroll(aid, dto, actor));
        assertTrue(ex.getMessage().contains("尚未加入任何小组"));
    }

    // ---------- helpers ----------

    private interface ActivityConfigurer {
        void apply(Activity a);
    }

    private Long insertActivity(ActivityConfigurer configurer) {
        Activity a = new Activity();
        a.setTitle("报名测试活动_" + System.nanoTime());
        a.setStartTime(A_START);
        a.setEndTime(A_START.plusHours(8));
        a.setStatus(1);
        a.setNeedAudit(0);
        a.setMinProjects(0);
        a.setRequireMinJoinCount(0);
        configurer.apply(a);
        activityMapper.insert(a);
        a.setSerialNo(a.getId());
        activityMapper.updateById(a);
        return a.getId();
    }

    private Long insertSlot(Long activityId, LocalDateTime start, LocalDateTime end) {
        ActivitySlot slot = new ActivitySlot();
        slot.setActivityId(activityId);
        slot.setProjectName("项目_" + System.nanoTime());
        slot.setStartTime(start);
        slot.setEndTime(end);
        slot.setNeedCount(5);
        activitySlotMapper.insert(slot);
        return slot.getId();
    }

    private Long insertVolunteer(Gender gender, LocalDate birthday, Grade grade) {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime());
        v.setRealName("测试志愿者");
        v.setGender(gender);
        v.setBirthday(birthday);
        v.setGrade(grade);
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    /** 插入一条「秘书部已确认」的考勤行（计入 sumConfirmedMinutes），用于「已参加时长门槛」测试。 */
    private void insertConfirmedAttendance(Long volunteerId, int minutes) {
        Long histAid = insertActivity(a -> a.setNeedAudit(0));
        ActivityAttendance att = new ActivityAttendance();
        att.setActivityId(histAid);
        att.setVolunteerId(volunteerId);
        att.setServiceMinutes(minutes);
        att.setSecretaryStatus(1);
        att.setPointsStatus(0);
        att.setPointsFactor(0);
        attendanceMapper.insert(att);
    }

    private void insertApprovedEnrollment(Long activityId, Long slotId, Long volunteerId) {
        ActivityEnrollment e = new ActivityEnrollment();
        e.setActivityId(activityId);
        e.setSlotId(slotId);
        e.setVolunteerId(volunteerId);
        e.setStatus(1);
        e.setEnrollTime(LocalDateTime.now());
        enrollmentMapper.insert(e);
    }

    /** 建一个 ACTIVE 小组：actor 为组长，其他人均为 ACTIVE 成员。返回 groupId。 */
    private Long insertGroupWithMembers(Long actorId, List<Long> otherMemberIds) {
        VolunteerGroup g = new VolunteerGroup();
        g.setGroupNo("G_" + System.nanoTime());
        g.setName("测试小组_" + System.nanoTime());
        g.setLeaderId(actorId);
        g.setStatus(1);
        groupMapper.insert(g);
        // 组长
        VolunteerGroupMember leader = new VolunteerGroupMember();
        leader.setGroupId(g.getId());
        leader.setVolunteerId(actorId);
        leader.setRole(1);
        leader.setStatus(1);
        leader.setApplyTime(LocalDateTime.now());
        leader.setAuditTime(LocalDateTime.now());
        groupMemberMapper.insert(leader);
        // 其他成员
        for (Long mid : otherMemberIds) {
            VolunteerGroupMember m = new VolunteerGroupMember();
            m.setGroupId(g.getId());
            m.setVolunteerId(mid);
            m.setRole(0);
            m.setStatus(1);
            m.setApplyTime(LocalDateTime.now());
            m.setAuditTime(LocalDateTime.now());
            groupMemberMapper.insert(m);
        }
        return g.getId();
    }

    private ActivityEnrollment latestEnrollment(Long activityId, Long volunteerId) {
        List<ActivityEnrollment> list = enrollmentMapper.selectList(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, activityId)
                .eq(ActivityEnrollment::getVolunteerId, volunteerId)
                .orderByDesc(ActivityEnrollment::getId));
        return list.get(0);
    }
}
