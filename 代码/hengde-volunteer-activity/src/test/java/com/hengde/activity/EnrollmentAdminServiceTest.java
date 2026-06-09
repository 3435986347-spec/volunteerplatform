package com.hengde.activity;

import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityEnrollment;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.service.EnrollmentAdminService;
import com.hengde.activity.vo.EnrollmentAdminVO;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.constant.Gender;
import com.hengde.common.constant.Grade;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.auth.dao.VolunteerMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端报名管理验证：手动新增越权、审核通过/拒绝、删除（逻辑删）、列表排序与志愿者信息 join。
 * MySQL + Redis 由 Testcontainers 起。<b>需本机有 Docker。</b>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class EnrollmentAdminServiceTest {

    private static final long ADMIN_ID = 999L;
    private static final LocalDateTime A_START = LocalDateTime.now().plusDays(7).withNano(0);

    @Autowired
    private EnrollmentAdminService adminService;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ActivitySlotMapper activitySlotMapper;
    @Autowired
    private ActivityEnrollmentMapper enrollmentMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;

    @Test
    void manualEnroll_skipsDeadlineAndEligibility() {
        // 未成年 + 报名已截止 + 活动要求 ≥18 岁：志愿者自助会被拒，但管理员越权补录应成功
        Long vid = insertVolunteer("未成年", null, Gender.MALE, LocalDate.now().minusYears(15), Grade.GRADE_9);
        Long aid = insertActivity(a -> {
            a.setNeedAudit(1); // 即便需审核，手动新增也直接置已通过
            a.setRequireMinAge(18);
            a.setEnrollDeadline(LocalDateTime.now().minusHours(1));
        });
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));

        int n = adminService.manualEnroll(aid, vid, List.of(slot), ADMIN_ID);

        assertEquals(1, n);
        ActivityEnrollment e = onlyEnrollment(aid, vid);
        assertEquals(1, e.getStatus(), "手动新增直接置已通过");
        assertEquals(ADMIN_ID, e.getAuditBy());
        assertNotNull(e.getAuditTime());
    }

    @Test
    void manualEnroll_stillRejectsDuplicate() {
        Long vid = insertVolunteer("重复", null, Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> a.setNeedAudit(0));
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));
        adminService.manualEnroll(aid, vid, List.of(slot), ADMIN_ID);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminService.manualEnroll(aid, vid, List.of(slot), ADMIN_ID));
        assertEquals("该志愿者已报名该活动", ex.getMessage());
    }

    @Test
    void manualEnroll_disabledVolunteer_rejected() {
        // 账号被禁用(status=1)：越权可跳过年龄/截止等资格条件，但「禁用」不是资格条件，仍须拦截
        Long vid = insertDisabledVolunteer();
        Long aid = insertActivity(a -> a.setNeedAudit(0));
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminService.manualEnroll(aid, vid, List.of(slot), ADMIN_ID));
        assertEquals("该志愿者账号状态异常，无法报名", ex.getMessage());
    }

    @Test
    void audit_secondOperationOnSameEnrollment_rejected() {
        // 先通过再拒绝：第二次操作目标已非待审核，条件更新不命中 → 被拒（模拟并发审核覆盖）
        Long vid = insertVolunteer("并发", null, Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> a.setNeedAudit(1));
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));
        Long eid = insertEnrollment(aid, slot, vid, 0);

        adminService.approve(eid, ADMIN_ID);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminService.reject(eid, "晚了一步", ADMIN_ID));
        assertEquals("该报名不在待审核状态，无法操作", ex.getMessage());
        // 第一次通过的结果不应被第二次覆盖
        assertEquals(1, enrollmentMapper.selectById(eid).getStatus());
    }

    @Test
    void approve_pendingToApproved() {
        Long vid = insertVolunteer("待审", null, Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> a.setNeedAudit(1));
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));
        Long eid = insertEnrollment(aid, slot, vid, 0);

        adminService.approve(eid, ADMIN_ID);

        ActivityEnrollment e = enrollmentMapper.selectById(eid);
        assertEquals(1, e.getStatus());
        assertEquals(ADMIN_ID, e.getAuditBy());
        assertNotNull(e.getAuditTime());
        // 纯 wrapper 更新需显式刷新 update_time：审核后它应与 audit_time 同刻
        assertEquals(e.getAuditTime(), e.getUpdateTime());
    }

    @Test
    void reject_pendingToRejected_withReason() {
        Long vid = insertVolunteer("拒绝", null, Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> a.setNeedAudit(1));
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));
        Long eid = insertEnrollment(aid, slot, vid, 0);

        adminService.reject(eid, "资料不符", ADMIN_ID);

        ActivityEnrollment e = enrollmentMapper.selectById(eid);
        assertEquals(2, e.getStatus());
        assertEquals("资料不符", e.getRejectReason());
        assertEquals(ADMIN_ID, e.getAuditBy());
    }

    @Test
    void approve_nonPending_rejected() {
        Long vid = insertVolunteer("已通过", null, Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> a.setNeedAudit(0));
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));
        Long eid = insertEnrollment(aid, slot, vid, 1); // 已通过

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminService.approve(eid, ADMIN_ID));
        assertEquals("该报名不在待审核状态，无法操作", ex.getMessage());
    }

    @Test
    void delete_logicalRemoval() {
        Long vid = insertVolunteer("删除", null, Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> a.setNeedAudit(0));
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));
        Long eid = insertEnrollment(aid, slot, vid, 1);

        adminService.delete(eid);

        assertNull(enrollmentMapper.selectById(eid), "逻辑删除后按 id 查不到");
        long remaining = adminService.list(aid, pageQuery(), null).getRecords().size();
        assertEquals(0, remaining, "列表不应再含已删记录");
    }

    @Test
    void list_ordersByEnrollTime_andJoinsVolunteerInfo() {
        Long vid = insertVolunteer("张三", "13800001234", Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> a.setNeedAudit(0));
        Long s1 = insertSlot(aid, A_START, A_START.plusHours(2));
        Long s2 = insertSlot(aid, A_START.plusHours(3), A_START.plusHours(5));
        // 故意让 s2 的报名时间更早，验证按 enroll_time 升序而非插入顺序
        insertEnrollmentAt(aid, s1, vid, 1, LocalDateTime.now());
        insertEnrollmentAt(aid, s2, vid, 1, LocalDateTime.now().minusHours(2));

        List<EnrollmentAdminVO> records = adminService.list(aid, pageQuery(), null).getRecords();

        assertEquals(2, records.size());
        assertEquals(s2, records.get(0).getSlotId(), "报名时间更早的应排在前");
        assertEquals("张三", records.get(0).getRealName());
        assertEquals("13800001234", records.get(0).getPhone(), "手机号应解密为明文");
    }

    @Test
    void listGlobal_crossActivity_filtersStatus_joinsTitle_ordersDesc() {
        Long vid = insertVolunteer("全局", null, Gender.MALE, LocalDate.now().minusYears(25), Grade.COLLEGE_1);
        Long aid = insertActivity(a -> a.setNeedAudit(1));
        Activity act = activityMapper.selectById(aid);
        Long slot = insertSlot(aid, A_START, A_START.plusHours(2));
        Long e1 = insertEnrollmentAt(aid, slot, vid, 1, LocalDateTime.now());              // 已通过，较新
        Long e2 = insertEnrollmentAt(aid, slot, vid, 1, LocalDateTime.now().minusHours(2)); // 已通过，较旧
        insertEnrollmentAt(aid, slot, vid, 0, LocalDateTime.now().minusHours(1));           // 待审——status=1 过滤应排除

        List<EnrollmentAdminVO> all = adminService.listGlobal(pageQuery(), 1).getRecords();
        List<EnrollmentAdminVO> mine = all.stream().filter(r -> aid.equals(r.getActivityId())).toList();

        assertEquals(2, mine.size(), "全局 status=1 过滤应排除该活动的待审记录");
        assertTrue(mine.stream().allMatch(r -> act.getTitle().equals(r.getActivityTitle())),
                "全局列表每行应带出活动标题");
        // 全局按 enroll_time 倒序：我的两条里较新的 e1 在前、较旧的 e2 在后
        assertEquals(e1, mine.get(0).getEnrollmentId(), "较新报名应排在前（倒序）");
        assertEquals(e2, mine.get(1).getEnrollmentId());
    }

    // ---------- helpers ----------

    private interface ActivityConfigurer {
        void apply(Activity a);
    }

    private PageQuery pageQuery() {
        PageQuery q = new PageQuery();
        q.setPage(1);
        q.setSize(50);
        return q;
    }

    private Long insertActivity(ActivityConfigurer configurer) {
        Activity a = new Activity();
        a.setTitle("报名管理测试_" + System.nanoTime());
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

    private Long insertVolunteer(String name, String phonePlain, Gender gender, LocalDate birthday, Grade grade) {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime());
        v.setRealName(name);
        if (phonePlain != null) {
            v.setPhone(cryptoUtil.encrypt(phonePlain));
            v.setPhoneHash(cryptoUtil.hashPhone(phonePlain));
        }
        v.setSchool("测试中学");
        v.setGender(gender);
        v.setBirthday(birthday);
        v.setGrade(grade);
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private Long insertDisabledVolunteer() {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime());
        v.setRealName("被禁用");
        v.setGender(Gender.MALE);
        v.setBirthday(LocalDate.now().minusYears(25));
        v.setGrade(Grade.COLLEGE_1);
        v.setStatus(1); // 禁用
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private Long insertEnrollment(Long activityId, Long slotId, Long volunteerId, int status) {
        return insertEnrollmentAt(activityId, slotId, volunteerId, status, LocalDateTime.now());
    }

    private Long insertEnrollmentAt(Long activityId, Long slotId, Long volunteerId, int status, LocalDateTime enrollTime) {
        ActivityEnrollment e = new ActivityEnrollment();
        e.setActivityId(activityId);
        e.setSlotId(slotId);
        e.setVolunteerId(volunteerId);
        e.setStatus(status);
        e.setEnrollTime(enrollTime);
        enrollmentMapper.insert(e);
        return e.getId();
    }

    private ActivityEnrollment onlyEnrollment(Long activityId, Long volunteerId) {
        List<EnrollmentAdminVO> records = adminService.list(activityId, pageQuery(), null).getRecords();
        assertTrue(records.size() >= 1);
        return enrollmentMapper.selectById(records.get(0).getEnrollmentId());
    }
}
