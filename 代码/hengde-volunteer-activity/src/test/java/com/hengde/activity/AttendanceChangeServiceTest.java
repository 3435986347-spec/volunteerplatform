package com.hengde.activity;

import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.service.ActivityChangeService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

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

    @Autowired
    private ActivityChangeService changeService;
    @Autowired
    private ActivityAttendanceMapper attendanceMapper;

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

    private Long insertAttendance(LocalDateTime checkIn, LocalDateTime checkOut, int minutes, int points) {
        return insertAttendance(checkIn, checkOut, minutes, points, 1);   // 默认积分已发放
    }

    private Long insertAttendance(LocalDateTime checkIn, LocalDateTime checkOut, int minutes, int points, int pointsStatus) {
        ActivityAttendance att = new ActivityAttendance();
        att.setActivityId(7001L);
        att.setVolunteerId(8001L + System.nanoTime() % 100000);
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
