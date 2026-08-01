package com.hengde.activity;

import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.service.ActivityChangeService;
import com.hengde.activity.vo.AttendanceChangeVO;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 考勤/积分变更二次审核（PR2）验证：申请不立即生效、通过才应用（改签退重算时长/改积分）、
 * 拒绝不应用、CAS 防重复审核、非待审不可再审、新值格式校验、改积分须已发放、审核人非空。
 * MySQL + Redis 由 Testcontainers 起。<b>需本机有 Docker。</b>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class AttendanceChangeServiceTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 5, 1, 9, 0, 0);
    private static final long REQUESTER = 300L;
    private static final long AUDITOR = 400L;

    /**
     * 志愿者 id 发号器。原先用 {@code System.nanoTime() % 100000} 造 id，配上固定的 activityId=7001，
     * 会概率性撞 {@code uk_activity_volunteer} 唯一键——本类曾因此偶发失败（重跑即过）。
     * 改为进程内单调自增，彻底消除偶发。
     */
    private static final AtomicLong VOLUNTEER_SEQ = new AtomicLong(System.nanoTime() % 1_000_000 * 1000);

    @Autowired
    private ActivityChangeService changeService;
    @Autowired
    private ActivityAttendanceMapper attendanceMapper;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ActivitySlotMapper slotMapper;

    @Test
    void requestChange_doesNotApplyImmediately() {
        Long attId = insertAttendance(BASE, BASE.plusHours(1), 60, 100);
        Long changeId = changeService.requestChange(attId, 3, "150", "补发", REQUESTER);

        ActivityAttendance att = attendanceMapper.selectById(attId);
        assertEquals(100, att.getPointsAward(), "申请阶段不应改动积分");
        assertTrue(changeId > 0);
    }

    @Test
    void approve_pointsChange_applied() {
        Long attId = insertAttendance(BASE, BASE.plusHours(1), 60, 100);
        Long changeId = changeService.requestChange(attId, 3, "150", "补发", REQUESTER);

        changeService.approve(changeId, "同意", AUDITOR);
        assertEquals(150, attendanceMapper.selectById(attId).getPointsAward(), "通过后应应用新积分");
    }

    @Test
    void approve_checkOutChange_recomputesMinutes() {
        Long attId = insertAttendance(BASE, BASE.plusHours(1), 60, 100);   // 原时长 60
        // 改签退时间到 BASE+3h → 时长应重算为 180
        Long changeId = changeService.requestChange(attId, 2, BASE.plusHours(3).toString(), "实际更晚结束", REQUESTER);

        changeService.approve(changeId, null, AUDITOR);
        ActivityAttendance att = attendanceMapper.selectById(attId);
        assertEquals(BASE.plusHours(3), att.getCheckOutTime());
        assertEquals(180, att.getServiceMinutes(), "改签退应按 签退−签到 重算时长");
    }

    @Test
    void reject_doesNotApply() {
        Long attId = insertAttendance(BASE, BASE.plusHours(1), 60, 100);
        Long changeId = changeService.requestChange(attId, 3, "150", "补发", REQUESTER);

        changeService.reject(changeId, "证据不足", AUDITOR);
        assertEquals(100, attendanceMapper.selectById(attId).getPointsAward(), "拒绝后不应改动积分");
    }

    @Test
    void approveTwice_secondRejected() {
        Long attId = insertAttendance(BASE, BASE.plusHours(1), 60, 100);
        Long changeId = changeService.requestChange(attId, 3, "150", "补发", REQUESTER);
        changeService.approve(changeId, null, AUDITOR);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> changeService.approve(changeId, null, AUDITOR));
        assertTrue(ex.getMessage().contains("已审核") || ex.getMessage().contains("已处理"));
    }

    @Test
    void rejectAfterApprove_rejected() {
        Long attId = insertAttendance(BASE, BASE.plusHours(1), 60, 100);
        Long changeId = changeService.requestChange(attId, 3, "150", "补发", REQUESTER);
        changeService.approve(changeId, null, AUDITOR);

        assertThrows(BusinessException.class, () -> changeService.reject(changeId, "x", AUDITOR));
    }

    @Test
    void requestChange_badTimeFormat_rejected() {
        Long attId = insertAttendance(BASE, BASE.plusHours(1), 60, 100);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> changeService.requestChange(attId, 1, "not-a-date", "x", REQUESTER));
        assertTrue(ex.getMessage().contains("时间格式"));
    }

    @Test
    void requestChange_attendanceNotExist_rejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> changeService.requestChange(99999999L, 3, "10", "x", REQUESTER));
        assertTrue(ex.getMessage().contains("考勤记录不存在"));
    }

    @Test
    void requestChange_pointsNotGranted_rejected() {
        // 积分未发放（points_status=0）时申请改积分应被拒，否则后续 grantPoints() 会覆盖修正
        Long attId = insertAttendance(BASE, BASE.plusHours(1), 60, 100, 0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> changeService.requestChange(attId, 3, "150", "补发", REQUESTER));
        assertTrue(ex.getMessage().contains("积分尚未发放"));
    }

    @Test
    void approve_nullAuditor_rejected() {
        Long attId = insertAttendance(BASE, BASE.plusHours(1), 60, 100);
        Long changeId = changeService.requestChange(attId, 3, "150", "补发", REQUESTER);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> changeService.approve(changeId, "x", null));
        assertTrue(ex.getMessage().contains("审核人"));
        assertEquals(100, attendanceMapper.selectById(attId).getPointsAward(), "审核人为空被拒，不应应用变更");
    }

    @Test
    void reject_nullAuditor_rejected() {
        Long attId = insertAttendance(BASE, BASE.plusHours(1), 60, 100);
        Long changeId = changeService.requestChange(attId, 3, "150", "补发", REQUESTER);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> changeService.reject(changeId, "x", null));
        assertTrue(ex.getMessage().contains("审核人"));
    }

    @Test
    void list_byStatus_returnsPending() {
        Long attId = insertAttendance(BASE, BASE.plusHours(1), 60, 100);
        changeService.requestChange(attId, 3, "150", "补发", REQUESTER);
        // 领域模块无分页拦截器，断言 records 内容而非 total
        var records = changeService.list(new PageQuery(), 0).getRecords();
        assertTrue(records.stream().anyMatch(v -> v.getAttendanceId().equals(attId) && v.getStatus() == 0));
        // 上下文：带出 activityId/volunteerId
        records.stream().filter(v -> v.getAttendanceId().equals(attId)).findFirst()
                .ifPresent(v -> assertNull(v.getAuditedTime()));
    }

    // ---------- helpers ----------

    /**
     * 审核列表必须带出<b>场次</b>：同一人同活动两场都申请改签到时，两行的「活动 + 姓名 + 变更项」完全相同；
     * 更要紧的是部长审的是「签到时间改成 X」，没有岗位起止就无从判断 X 合不合理。
     *
     * <p>依据 xlsx Row 32 C：「组织部可以在活动后台修改其签到、签退时间和奖励积分，该功能操作需要部长二次审核」。</p>
     */
    @Test
    void list_sameVolunteerTwoSlots_rowsCarrySlotContext() {
        LocalDateTime day = LocalDateTime.of(2026, 5, 2, 9, 0, 0);
        Long aid = insertRealActivity(day, day.plusHours(9));
        Long morning = insertRealSlot(aid, "上午岗", day, day.plusHours(3));
        Long afternoon = insertRealSlot(aid, "下午岗", day.plusHours(5), day.plusHours(9));
        Long vid = 8001L + VOLUNTEER_SEQ.incrementAndGet();

        Long attMorning = insertAttendanceOnSlot(aid, morning, vid, day, day.plusHours(3));
        Long attAfternoon = insertAttendanceOnSlot(aid, afternoon, vid, day.plusHours(5), day.plusHours(9));
        changeService.requestChange(attMorning, 1, day.plusMinutes(5).toString(), "迟到修正", REQUESTER);
        changeService.requestChange(attAfternoon, 1, day.plusHours(5).plusMinutes(5).toString(), "迟到修正", REQUESTER);

        var rows = changeService.list(new PageQuery(), 0).getRecords().stream()
                .filter(r -> vid.equals(r.getVolunteerId())).toList();
        assertEquals(2, rows.size(), "两场各一条申请");
        assertEquals(2, rows.stream().map(AttendanceChangeVO::getSlotId).distinct().count(),
                "两行必须能靠 slotId 区分，否则审核界面上完全一样");

        AttendanceChangeVO m = rows.stream().filter(r -> morning.equals(r.getSlotId())).findFirst().orElseThrow();
        assertEquals("上午岗", m.getSlotProjectName());
        assertEquals(day, m.getSlotStartTime(), "要给出岗位起止作为「新值是否合理」的参照");
        assertEquals(day.plusHours(3), m.getSlotEndTime());

        AttendanceChangeVO a = rows.stream().filter(r -> afternoon.equals(r.getSlotId())).findFirst().orElseThrow();
        assertEquals("下午岗", a.getSlotProjectName());
        assertEquals(day.plusHours(5), a.getSlotStartTime());
    }

    private Long insertRealActivity(LocalDateTime start, LocalDateTime end) {
        Activity a = new Activity();
        a.setTitle("变更审核场次用例_" + System.nanoTime());
        a.setStartTime(start);
        a.setEndTime(end);
        a.setStatus(1);
        a.setRunStatus(0);
        a.setNeedAudit(0);
        a.setMinProjects(0);
        a.setRequireMinJoinCount(0);
        a.setPointsBase(100);
        activityMapper.insert(a);
        return a.getId();
    }

    private Long insertRealSlot(Long activityId, String name, LocalDateTime start, LocalDateTime end) {
        ActivitySlot s = new ActivitySlot();
        s.setActivityId(activityId);
        s.setProjectName(name);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setNeedCount(10);
        slotMapper.insert(s);
        return s.getId();
    }

    private Long insertAttendanceOnSlot(Long activityId, Long slotId, Long volunteerId,
                                        LocalDateTime checkIn, LocalDateTime checkOut) {
        ActivityAttendance att = new ActivityAttendance();
        att.setActivityId(activityId);
        att.setSlotId(slotId);
        att.setVolunteerId(volunteerId);
        att.setCheckInTime(checkIn);
        att.setCheckOutTime(checkOut);
        att.setServiceMinutes(180);
        att.setAttendStatus(1);
        att.setPointsAward(100);
        att.setSecretaryStatus(1);
        att.setPointsStatus(1);
        att.setPointsFactor(0);
        attendanceMapper.insert(att);
        return att.getId();
    }

    private Long insertAttendance(LocalDateTime checkIn, LocalDateTime checkOut, int minutes, int points) {
        return insertAttendance(checkIn, checkOut, minutes, points, 1);   // 默认积分已发放
    }

    private Long insertAttendance(LocalDateTime checkIn, LocalDateTime checkOut, int minutes, int points, int pointsStatus) {
        ActivityAttendance att = new ActivityAttendance();
        att.setActivityId(7001L);
        // V30：slot_id NOT NULL。本用例只测「考勤变更」逻辑、不涉及真实活动/场次，
        // 故用与 activityId 同源的合成场次 id（表上无外键，语义与该用例的 7001L 活动一致）。
        att.setSlotId(7101L);
        att.setVolunteerId(8001L + VOLUNTEER_SEQ.incrementAndGet());
        att.setCheckInTime(checkIn);
        att.setCheckOutTime(checkOut);
        att.setServiceMinutes(minutes);
        att.setAttendStatus(1);   // 正常到位 → 改时间会重算
        att.setPointsAward(points);
        att.setSecretaryStatus(1);
        att.setPointsStatus(pointsStatus);
        att.setPointsFactor(0);
        attendanceMapper.insert(att);
        return att.getId();
    }
}
