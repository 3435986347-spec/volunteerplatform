package com.hengde.activity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityBackfillMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.dto.ActivityCreateDTO;
import com.hengde.activity.dto.ActivitySlotDTO;
import com.hengde.activity.dto.BackfillRequestDTO;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.service.ActivityBackfillService;
import com.hengde.activity.service.ActivityService;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史活动发布 + 活动补录 + 部长审核（第 3 批·PR3）验证：历史发布标记/按手机号·身份证解析/未匹配·非本活动·
 * 已有考勤拒/通过普通活动发积分·历史只记时长/拒绝不落账/审核人非空/CAS 防重复审核。
 * MySQL + Redis 由 Testcontainers 起。<b>需本机有 Docker。</b>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class ActivityBackfillServiceTest {

    private static final long ADMIN = 100L;
    private static final long REQUESTER = 300L;
    private static final long AUDITOR = 400L;
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private ActivityService activityService;
    @Autowired
    private ActivityBackfillService backfillService;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ActivitySlotMapper slotMapper;
    @Autowired
    private ActivityAttendanceMapper attendanceMapper;
    @Autowired
    private ActivityBackfillMapper backfillMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;

    @Test
    void publishHistorical_setsFlagAndFinished() {
        Long id = activityService.publishHistorical(templateDto(), ADMIN);
        Activity a = activityMapper.selectById(id);
        assertEquals(1, a.getIsHistorical());
        assertEquals(2, a.getStatus(), "历史活动应为已结束态");
        assertEquals(2, a.getRunStatus(), "历史活动 run_status 直接为已结束");
    }

    @Test
    void requestBackfill_byPhone_pending_computesMinutes() {
        Long aid = insertActivity(1, 0, 10);
        Long slot = insertSlot(aid, at(9), at(10));   // 60 分钟
        String phone = uniquePhone();
        insertVolunteer("补录甲", phone, null);

        Long bfId = backfillService.requestBackfill(aid, req(phone, null, null, slot), REQUESTER);
        var bf = backfillMapper.selectById(bfId);
        assertEquals(60, bf.getServiceMinutes());
        assertEquals(1, bf.getGrantPoints(), "普通活动补录得积分");
        assertEquals(0, bf.getStatus(), "申请阶段待审");
    }

    @Test
    void requestBackfill_byIdCard_resolvesVolunteer() {
        Long aid = insertActivity(1, 0, 10);
        Long slot = insertSlot(aid, at(9), at(10));
        String idCard = uniqueIdCard();
        Long vid = insertVolunteer("补录乙", null, idCard);

        Long bfId = backfillService.requestBackfill(aid, req(null, idCard, null, slot), REQUESTER);
        assertEquals(vid, backfillMapper.selectById(bfId).getVolunteerId());
    }

    @Test
    void requestBackfill_notFound_rejected() {
        Long aid = insertActivity(1, 0, 10);
        Long slot = insertSlot(aid, at(9), at(10));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> backfillService.requestBackfill(aid, req(uniquePhone(), null, null, slot), REQUESTER));
        assertTrue(ex.getMessage().contains("未匹配到志愿者"));
    }

    @Test
    void requestBackfill_slotNotInActivity_rejected() {
        Long aid = insertActivity(1, 0, 10);
        Long otherAid = insertActivity(1, 0, 10);
        Long foreignSlot = insertSlot(otherAid, at(9), at(10));
        String phone = uniquePhone();
        insertVolunteer("补录丙", phone, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> backfillService.requestBackfill(aid, req(phone, null, null, foreignSlot), REQUESTER));
        assertTrue(ex.getMessage().contains("不属于该活动") || ex.getMessage().contains("时间段不存在"));
    }

    @Test
    void requestBackfill_existingAttendance_rejected() {
        Long aid = insertActivity(1, 0, 10);
        Long slot = insertSlot(aid, at(9), at(10));
        String phone = uniquePhone();
        Long vid = insertVolunteer("补录丁", phone, null);
        insertAttendance(aid, vid);   // 已有考勤

        BusinessException ex = assertThrows(BusinessException.class,
                () -> backfillService.requestBackfill(aid, req(phone, null, null, slot), REQUESTER));
        assertTrue(ex.getMessage().contains("已有该活动的考勤记录"));
    }

    @Test
    void approve_normalActivity_confirmedAndPointsGranted() {
        Long aid = insertActivity(1, 0, 10);
        Long slot = insertSlot(aid, at(9), at(10));   // 60 分钟
        String phone = uniquePhone();
        Long vid = insertVolunteer("补录戊", phone, null);
        Long bfId = backfillService.requestBackfill(aid, req(phone, null, null, slot), REQUESTER);

        backfillService.approve(bfId, "同意", AUDITOR);
        ActivityAttendance att = loadAttendance(aid, vid);
        assertEquals(60, att.getServiceMinutes());
        assertEquals(1, att.getSecretaryStatus(), "通过即终态，秘书确认置 1");
        assertEquals(1, att.getPointsStatus(), "普通活动补录发积分");
        assertEquals(10, att.getPointsAward(), "基数10×1.0×1.0");
    }

    @Test
    void approve_historicalActivity_minutesOnly_noPoints() {
        Long aid = insertActivity(2, 1, 10);          // 历史活动
        Long slot = insertSlot(aid, at(9), at(11));   // 120 分钟
        String phone = uniquePhone();
        Long vid = insertVolunteer("补录己", phone, null);
        Long bfId = backfillService.requestBackfill(aid, req(phone, null, null, slot), REQUESTER);
        assertEquals(0, backfillMapper.selectById(bfId).getGrantPoints(), "历史活动不发积分");

        backfillService.approve(bfId, null, AUDITOR);
        ActivityAttendance att = loadAttendance(aid, vid);
        assertEquals(120, att.getServiceMinutes());
        assertEquals(1, att.getSecretaryStatus());
        assertEquals(0, att.getPointsStatus(), "历史活动只记时长不发积分");
        assertEquals(0, att.getPointsAward());
    }

    @Test
    void reject_doesNotCreateAttendance() {
        Long aid = insertActivity(1, 0, 10);
        Long slot = insertSlot(aid, at(9), at(10));
        String phone = uniquePhone();
        Long vid = insertVolunteer("补录庚", phone, null);
        Long bfId = backfillService.requestBackfill(aid, req(phone, null, null, slot), REQUESTER);

        backfillService.reject(bfId, "证据不足", AUDITOR);
        assertEquals(2, backfillMapper.selectById(bfId).getStatus());
        assertNull(loadAttendance(aid, vid), "拒绝后不应落考勤行");
    }

    @Test
    void approve_nullAuditor_rejected() {
        Long aid = insertActivity(1, 0, 10);
        Long slot = insertSlot(aid, at(9), at(10));
        String phone = uniquePhone();
        Long vid = insertVolunteer("补录辛", phone, null);
        Long bfId = backfillService.requestBackfill(aid, req(phone, null, null, slot), REQUESTER);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> backfillService.approve(bfId, "x", null));
        assertTrue(ex.getMessage().contains("审核人"));
        assertNull(loadAttendance(aid, vid), "审核人为空被拒，不应落账");
    }

    @Test
    void approveTwice_secondRejected() {
        Long aid = insertActivity(1, 0, 10);
        Long slot = insertSlot(aid, at(9), at(10));
        String phone = uniquePhone();
        insertVolunteer("补录壬", phone, null);
        Long bfId = backfillService.requestBackfill(aid, req(phone, null, null, slot), REQUESTER);
        backfillService.approve(bfId, null, AUDITOR);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> backfillService.approve(bfId, null, AUDITOR));
        assertTrue(ex.getMessage().contains("已审核") || ex.getMessage().contains("已被处理"));
    }

    // ---------- helpers ----------

    private BackfillRequestDTO req(String phone, String idCard, String name, Long slotId) {
        BackfillRequestDTO dto = new BackfillRequestDTO();
        dto.setPhone(phone);
        dto.setIdCard(idCard);
        dto.setName(name);
        dto.setSlotId(slotId);
        return dto;
    }

    private LocalDateTime at(int hour) {
        return LocalDateTime.of(2026, 3, 1, hour, 0);
    }

    private Long insertActivity(int status, Integer isHistorical, int pointsBase) {
        Activity a = new Activity();
        a.setTitle("补录活动_" + System.nanoTime());
        a.setStartTime(at(8));
        a.setEndTime(at(18));
        a.setStatus(status);
        a.setIsHistorical(isHistorical);
        a.setPointsBase(pointsBase);
        activityMapper.insert(a);
        a.setSerialNo(a.getId());
        activityMapper.updateById(a);
        return a.getId();
    }

    private Long insertSlot(Long activityId, LocalDateTime start, LocalDateTime end) {
        ActivitySlot s = new ActivitySlot();
        s.setActivityId(activityId);
        s.setProjectName("项目_" + System.nanoTime());
        s.setStartTime(start);
        s.setEndTime(end);
        s.setNeedCount(10);
        slotMapper.insert(s);
        return s.getId();
    }

    private Long insertVolunteer(String name, String phone, String idCard) {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime());
        v.setRealName(name);
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        if (phone != null) {
            v.setPhone(cryptoUtil.encrypt(phone));
            v.setPhoneHash(cryptoUtil.hashPhone(phone));
        }
        if (idCard != null) {
            v.setIdCardNo(cryptoUtil.encrypt(idCard));
            v.setIdCardHash(cryptoUtil.hashIdCard(idCard));
        }
        volunteerMapper.insert(v);
        return v.getId();
    }

    private void insertAttendance(Long activityId, Long volunteerId) {
        ActivityAttendance att = new ActivityAttendance();
        att.setActivityId(activityId);
        att.setVolunteerId(volunteerId);
        att.setAttendStatus(1);
        att.setSecretaryStatus(0);
        att.setPointsStatus(0);
        att.setPointsFactor(0);
        attendanceMapper.insert(att);
    }

    private ActivityAttendance loadAttendance(Long activityId, Long volunteerId) {
        return attendanceMapper.selectOne(Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getActivityId, activityId)
                .eq(ActivityAttendance::getVolunteerId, volunteerId));
    }

    private String uniquePhone() {
        return "138" + String.format("%08d", SEQ.incrementAndGet() % 100000000);
    }

    private String uniqueIdCard() {
        return "4408" + String.format("%014d", (long) SEQ.incrementAndGet());
    }

    private ActivityCreateDTO templateDto() {
        ActivitySlotDTO slot = new ActivitySlotDTO();
        slot.setProjectName("历史项目");
        slot.setStartTime(LocalDateTime.of(2026, 1, 10, 9, 0));
        slot.setEndTime(LocalDateTime.of(2026, 1, 10, 10, 0));
        slot.setNeedCount(5);

        ActivityCreateDTO t = new ActivityCreateDTO();
        t.setTitle("历史活动_" + System.nanoTime());
        t.setStartTime(LocalDateTime.of(2026, 1, 10, 9, 0));
        t.setEndTime(LocalDateTime.of(2026, 1, 10, 11, 0));
        t.setPointsBase(10);
        t.setSlots(List.of(slot));
        return t;
    }
}
