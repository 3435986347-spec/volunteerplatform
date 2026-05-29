package com.hengde.activity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.dao.ActivityViolationMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.ActivityEnrollment;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.entity.ActivityViolation;
import com.hengde.activity.dto.ActivityCreateDTO;
import com.hengde.activity.dto.ActivitySlotDTO;
import com.hengde.activity.service.ActivityLeaderService;
import com.hengde.activity.service.ActivityService;
import com.hengde.activity.service.AttendanceService;
import com.hengde.activity.service.ServiceRecordService;
import com.hengde.activity.vo.ActivityAdminDetailVO;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 签到/签退/服务时长/积分发放闭环 + 现场负责人的集成验证（V1.1 第 1 批）。
 * MySQL + Redis 由 Testcontainers 起。<b>需本机有 Docker。</b>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class ActivityAttendanceServiceTest {

    /** 活动坐标（雷州附近）；NEAR=同点、FAR=约 111km 外 */
    private static final BigDecimal ACT_LAT = new BigDecimal("21.2707");
    private static final BigDecimal ACT_LNG = new BigDecimal("110.0973");
    private static final BigDecimal FAR_LAT = new BigDecimal("22.2707");

    @Autowired
    private ActivityService activityService;
    @Autowired
    private ActivityLeaderService leaderService;
    @Autowired
    private AttendanceService attendanceService;
    @Autowired
    private ServiceRecordService serviceRecordService;
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

    // ---------- 负责人指派 ----------

    @Test
    void assignLeader_volunteerNotEnrolled_rejected() {
        Long aid = insertInProgressActivity();
        Long vid = insertVolunteer();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> leaderService.assign(aid, 1, vid, 100L));
        assertTrue(ex.getMessage().contains("报名志愿者"));
    }

    @Test
    void assignLeader_enrolledVolunteer_ok() {
        Long aid = insertInProgressActivity();
        Long vid = insertVolunteer();
        approveEnroll(aid, vid);
        Long leaderId = leaderService.assign(aid, 1, vid, 100L);
        assertNotNull(leaderId);
        assertTrue(leaderService.isVolunteerLeader(aid, vid));
    }

    // ---------- 主干闭环 happy path ----------

    @Test
    void closedLoop_normalVolunteer_grants_base_points() {
        Long aid = insertInProgressActivity();
        Long vid = insertVolunteer();
        approveEnroll(aid, vid);

        attendanceService.startActivity(aid, 100L);
        attendanceService.checkIn(aid, vid, ACT_LAT, ACT_LNG, 2);   // 同点，半径内
        int signedOut = attendanceService.bulkCheckOut(aid, null, 100L);
        assertEquals(1, signedOut);

        ActivityAttendance att = findAtt(aid, vid);
        assertNotNull(att.getCheckInTime());
        assertNotNull(att.getCheckOutTime());
        assertNotNull(att.getServiceMinutes());
        assertTrue(att.getServiceMinutes() >= 0);

        serviceRecordService.secretaryConfirm(att.getId(), 200L);
        int award = serviceRecordService.grantPoints(att.getId(), 0, 200L);
        assertEquals(100, award, "普通志愿者 = 基数 ×1.0 ×正常");

        ActivityAttendance after = attendanceMapper.selectById(att.getId());
        assertEquals(1, after.getSecretaryStatus());
        assertEquals(1, after.getPointsStatus());
        assertEquals(100, after.getPointsAward());
    }

    @Test
    void closedLoop_leader_gets_1_4x_points() {
        Long aid = insertInProgressActivity();
        Long vid = insertVolunteer();
        approveEnroll(aid, vid);
        leaderService.assign(aid, 1, vid, 100L);   // 该志愿者即现场负责人

        attendanceService.checkIn(aid, vid, ACT_LAT, ACT_LNG, 1);
        attendanceService.bulkCheckOut(aid, null, 100L);
        ActivityAttendance att = findAtt(aid, vid);
        serviceRecordService.secretaryConfirm(att.getId(), 200L);

        int award = serviceRecordService.grantPoints(att.getId(), 0, 200L);
        assertEquals(140, award, "负责人 = 基数100 ×1.4");
    }

    @Test
    void closedLoop_manager_gets_1_2x_points() {
        Long aid = insertInProgressActivity();
        Long vid = insertManagerVolunteer();   // manager_flag=1
        approveEnroll(aid, vid);

        attendanceService.checkIn(aid, vid, ACT_LAT, ACT_LNG, 2);
        attendanceService.bulkCheckOut(aid, null, 100L);
        ActivityAttendance att = findAtt(aid, vid);
        serviceRecordService.secretaryConfirm(att.getId(), 200L);

        int award = serviceRecordService.grantPoints(att.getId(), 0, 200L);
        assertEquals(120, award, "管理团队 = 基数100 ×1.2");
    }

    @Test
    void grantPoints_violationFactor_halfAndNone() {
        // 减半
        Long aid = insertInProgressActivity();
        Long vid = insertVolunteer();
        approveEnroll(aid, vid);
        attendanceService.checkIn(aid, vid, ACT_LAT, ACT_LNG, 2);
        attendanceService.bulkCheckOut(aid, null, 100L);
        ActivityAttendance att = findAtt(aid, vid);
        serviceRecordService.secretaryConfirm(att.getId(), 200L);
        assertEquals(50, serviceRecordService.grantPoints(att.getId(), 1, 200L), "减半=100×0.5");

        // 不发
        Long aid2 = insertInProgressActivity();
        Long vid2 = insertVolunteer();
        approveEnroll(aid2, vid2);
        attendanceService.checkIn(aid2, vid2, ACT_LAT, ACT_LNG, 2);
        attendanceService.bulkCheckOut(aid2, null, 100L);
        ActivityAttendance att2 = findAtt(aid2, vid2);
        serviceRecordService.secretaryConfirm(att2.getId(), 200L);
        assertEquals(0, serviceRecordService.grantPoints(att2.getId(), 2, 200L), "不发=0");
    }

    // ---------- 签到校验 ----------

    @Test
    void checkIn_outOfRadius_rejected() {
        Long aid = insertInProgressActivity();
        Long vid = insertVolunteer();
        approveEnroll(aid, vid);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> attendanceService.checkIn(aid, vid, FAR_LAT, ACT_LNG, 2));
        assertTrue(ex.getMessage().contains("签到范围"));
    }

    @Test
    void checkIn_beforeWindow_rejected() {
        // 活动 5h 后才开始 → 签到窗口(开始前2h)未到
        Long aid = insertActivity(LocalDateTime.now().plusHours(5), LocalDateTime.now().plusHours(8));
        Long vid = insertVolunteer();
        approveEnroll(aid, vid);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> attendanceService.checkIn(aid, vid, ACT_LAT, ACT_LNG, 2));
        assertTrue(ex.getMessage().contains("未到签到时间"));
    }

    @Test
    void checkIn_notEnrolled_rejected() {
        Long aid = insertInProgressActivity();
        Long vid = insertVolunteer();   // 未报名
        BusinessException ex = assertThrows(BusinessException.class,
                () -> attendanceService.checkIn(aid, vid, ACT_LAT, ACT_LNG, 2));
        assertTrue(ex.getMessage().contains("未报名"));
    }

    @Test
    void checkIn_duplicate_rejected() {
        Long aid = insertInProgressActivity();
        Long vid = insertVolunteer();
        approveEnroll(aid, vid);
        attendanceService.checkIn(aid, vid, ACT_LAT, ACT_LNG, 2);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> attendanceService.checkIn(aid, vid, ACT_LAT, ACT_LNG, 2));
        assertTrue(ex.getMessage().contains("已签到"));
    }

    // ---------- 到位状态 / 违规 ----------

    @Test
    void markAbsent_autoViolation_andZeroMinutes() {
        Long aid = insertInProgressActivity();
        Long vid = insertVolunteer();
        approveEnroll(aid, vid);

        attendanceService.markAttendStatus(aid, vid, 4, 100L);   // 缺席

        ActivityAttendance att = findAtt(aid, vid);
        assertEquals(4, att.getAttendStatus());
        assertEquals(0, att.getServiceMinutes());
        Long violations = violationMapper.selectCount(Wrappers.<ActivityViolation>lambdaQuery()
                .eq(ActivityViolation::getActivityId, aid)
                .eq(ActivityViolation::getVolunteerId, vid)
                .eq(ActivityViolation::getViolationType, 5));
        assertEquals(1L, violations, "缺席应自动记一条违规");

        // 重复标缺席不重复记违规
        attendanceService.markAttendStatus(aid, vid, 4, 100L);
        Long again = violationMapper.selectCount(Wrappers.<ActivityViolation>lambdaQuery()
                .eq(ActivityViolation::getActivityId, aid)
                .eq(ActivityViolation::getVolunteerId, vid)
                .eq(ActivityViolation::getViolationType, 5));
        assertEquals(1L, again);
    }

    // ---------- 秘书确认 / 积分发放 的次序与幂等 ----------

    @Test
    void grantBeforeConfirm_rejected() {
        Long aid = insertInProgressActivity();
        Long vid = insertVolunteer();
        approveEnroll(aid, vid);
        attendanceService.checkIn(aid, vid, ACT_LAT, ACT_LNG, 2);
        attendanceService.bulkCheckOut(aid, null, 100L);
        ActivityAttendance att = findAtt(aid, vid);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> serviceRecordService.grantPoints(att.getId(), 0, 200L));
        assertTrue(ex.getMessage().contains("秘书部确认"));
    }

    @Test
    void confirmTwice_and_grantTwice_rejected() {
        Long aid = insertInProgressActivity();
        Long vid = insertVolunteer();
        approveEnroll(aid, vid);
        attendanceService.checkIn(aid, vid, ACT_LAT, ACT_LNG, 2);
        attendanceService.bulkCheckOut(aid, null, 100L);
        ActivityAttendance att = findAtt(aid, vid);

        serviceRecordService.secretaryConfirm(att.getId(), 200L);
        assertThrows(BusinessException.class, () -> serviceRecordService.secretaryConfirm(att.getId(), 200L));

        serviceRecordService.grantPoints(att.getId(), 0, 200L);
        assertThrows(BusinessException.class, () -> serviceRecordService.grantPoints(att.getId(), 0, 200L));
    }

    @Test
    void confirm_withoutCheckOut_rejected() {
        Long aid = insertInProgressActivity();
        Long vid = insertVolunteer();
        approveEnroll(aid, vid);
        attendanceService.checkIn(aid, vid, ACT_LAT, ACT_LNG, 2);   // 只签到未签退
        ActivityAttendance att = findAtt(aid, vid);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> serviceRecordService.secretaryConfirm(att.getId(), 200L));
        assertTrue(ex.getMessage().contains("未签退") || ex.getMessage().contains("不存在"));
    }

    // ---------- 发布入参带坐标 → 可签到（回归 High finding） ----------

    @Test
    void publishWithCoords_thenCheckIn_ok() {
        // 走真实发布入参 ActivityService.publish 配置签到坐标，再验证管理端详情回显 + GPS 签到可成功
        LocalDateTime start = LocalDateTime.now().minusHours(1);
        LocalDateTime end = LocalDateTime.now().plusHours(1);
        ActivitySlotDTO slot = new ActivitySlotDTO();
        slot.setProjectName("项目A");
        slot.setStartTime(start);
        slot.setEndTime(end);
        slot.setNeedCount(10);
        ActivityCreateDTO dto = new ActivityCreateDTO();
        dto.setTitle("带坐标活动_" + System.nanoTime());
        dto.setStartTime(start);
        dto.setEndTime(end);
        dto.setLat(ACT_LAT);
        dto.setLng(ACT_LNG);
        dto.setSlots(List.of(slot));

        Long aid = activityService.publish(dto, 100L);

        // 管理端详情回显坐标 + 默认半径 500
        ActivityAdminDetailVO admin = activityService.detailForAdmin(aid);
        assertEquals(0, ACT_LAT.compareTo(admin.getLat()), "管理端详情应回显纬度");
        assertEquals(0, ACT_LNG.compareTo(admin.getLng()), "管理端详情应回显经度");
        assertEquals(500, admin.getCheckInRadiusM().intValue(), "半径不填默认 500");

        // 报名(已通过) + GPS 签到成功（不再「活动未设置签到坐标」）
        Long vid = insertVolunteer();
        approveEnroll(aid, vid);
        attendanceService.checkIn(aid, vid, ACT_LAT, ACT_LNG, 2);
        assertNotNull(findAtt(aid, vid).getCheckInTime(), "发布带坐标后应能签到");
    }

    // ---------- 活动开始/结束 ----------

    @Test
    void finishBeforeStart_rejected() {
        Long aid = insertInProgressActivity();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> attendanceService.finishActivity(aid, 100L));
        assertTrue(ex.getMessage().contains("未在进行中"));
    }

    // ---------- helpers ----------

    private Long insertInProgressActivity() {
        return insertActivity(LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
    }

    private Long insertActivity(LocalDateTime start, LocalDateTime end) {
        Activity a = new Activity();
        a.setTitle("闭环测试活动_" + System.nanoTime());
        a.setStartTime(start);
        a.setEndTime(end);
        a.setStatus(1);
        a.setRunStatus(0);
        a.setNeedAudit(0);
        a.setMinProjects(0);
        a.setRequireMinJoinCount(0);
        a.setPointsBase(100);
        a.setLeaderMultiplier(new BigDecimal("1.4"));
        a.setManagerMultiplier(new BigDecimal("1.2"));
        a.setLat(ACT_LAT);
        a.setLng(ACT_LNG);
        a.setCheckInRadiusM(500);
        activityMapper.insert(a);
        a.setSerialNo(a.getId());
        activityMapper.updateById(a);
        return a.getId();
    }

    private Long insertSlot(Long activityId) {
        ActivitySlot slot = new ActivitySlot();
        slot.setActivityId(activityId);
        slot.setProjectName("项目_" + System.nanoTime());
        slot.setStartTime(LocalDateTime.now());
        slot.setEndTime(LocalDateTime.now().plusHours(2));
        slot.setNeedCount(10);
        slotMapper.insert(slot);
        return slot.getId();
    }

    private void approveEnroll(Long activityId, Long volunteerId) {
        ActivityEnrollment e = new ActivityEnrollment();
        e.setActivityId(activityId);
        e.setSlotId(insertSlot(activityId));
        e.setVolunteerId(volunteerId);
        e.setStatus(1);   // 已通过
        e.setEnrollTime(LocalDateTime.now());
        enrollmentMapper.insert(e);
    }

    private Long insertVolunteer() {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime());
        v.setRealName("测试志愿者");
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    /** 插入一个被标记为管理团队（manager_flag=1）的志愿者。 */
    private Long insertManagerVolunteer() {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime());
        v.setRealName("管理团队志愿者");
        v.setStatus(0);
        v.setManagerFlag(1);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private ActivityAttendance findAtt(Long activityId, Long volunteerId) {
        return attendanceMapper.selectOne(Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getActivityId, activityId)
                .eq(ActivityAttendance::getVolunteerId, volunteerId)
                .last("limit 1"));
    }
}
