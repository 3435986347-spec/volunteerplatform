package com.hengde.activity;

import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityEnrollment;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.service.ActivityLeaderService;
import com.hengde.activity.service.AttendanceService;
import com.hengde.activity.service.MyActivityService;
import com.hengde.activity.vo.MyActivityDetailVO;
import com.hengde.activity.vo.MyActivityVO;
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
 * 「我的活动」（参与者视角）验证：仅含已通过报名、详情带考勤/负责人/二维码数据、非本人活动拒绝。
 * MySQL + Redis 由 Testcontainers 起（activity 上下文含 Redisson 依赖）。<b>需本机有 Docker。</b>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class MyActivityServiceTest {

    private static final BigDecimal ACT_LAT = new BigDecimal("21.2707");
    private static final BigDecimal ACT_LNG = new BigDecimal("110.0973");

    @Autowired
    private MyActivityService myActivityService;
    @Autowired
    private AttendanceService attendanceService;
    @Autowired
    private ActivityLeaderService leaderService;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ActivitySlotMapper slotMapper;
    @Autowired
    private ActivityEnrollmentMapper enrollmentMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;

    @Test
    void myActivities_onlyApprovedEnrollment() {
        Long vid = insertVolunteer();
        Long approved = insertActivity();
        enroll(approved, vid, 1);   // 已通过
        Long pending = insertActivity();
        enroll(pending, vid, 0);    // 待审核 → 不应出现

        List<MyActivityVO> list = myActivityService.myActivities(vid);
        List<Long> ids = list.stream().map(MyActivityVO::getActivityId).toList();
        assertTrue(ids.contains(approved), "已通过报名的活动应出现");
        assertTrue(!ids.contains(pending), "未通过报名的活动不应出现");
    }

    @Test
    void myActivityDetail_hasAttendanceLeaderAndQr() {
        Long vid = insertVolunteer();
        Long aid = insertActivity();
        enroll(aid, vid, 1);
        leaderService.assign(aid, 1, vid, 100L);   // 该志愿者也是负责人 → leaders 带名字
        attendanceService.checkIn(aid, vid, ACT_LAT, ACT_LNG, 2);

        MyActivityDetailVO vo = myActivityService.myActivityDetail(vid, aid);
        assertEquals(aid, vo.getActivityId());
        assertNotNull(vo.getCheckInTime(), "应带出签到时间");
        assertEquals("hengde-activity-checkin:" + aid, vo.getCheckInQrContent());
        assertNotNull(vo.getLeaders());
        assertTrue(vo.getLeaders().stream().anyMatch(l -> vid.equals(l.getVolunteerId())));
        assertEquals(0, ACT_LAT.compareTo(vo.getLat()), "详情回显活动坐标供前端 GPS 签到");
    }

    @Test
    void myActivityDetail_notMine_rejected() {
        Long owner = insertVolunteer();
        Long stranger = insertVolunteer();
        Long aid = insertActivity();
        enroll(aid, owner, 1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> myActivityService.myActivityDetail(stranger, aid));
        assertTrue(ex.getMessage().contains("未参加") || ex.getMessage().contains("不存在"));
    }

    // ---------- helpers ----------

    private Long insertActivity() {
        Activity a = new Activity();
        a.setTitle("我的活动测试_" + System.nanoTime());
        a.setStartTime(LocalDateTime.now().minusHours(1));
        a.setEndTime(LocalDateTime.now().plusHours(1));
        a.setStatus(1);
        a.setRunStatus(0);
        a.setLat(ACT_LAT);
        a.setLng(ACT_LNG);
        a.setCheckInRadiusM(500);
        activityMapper.insert(a);
        a.setSerialNo(a.getId());
        activityMapper.updateById(a);
        return a.getId();
    }

    private void enroll(Long activityId, Long volunteerId, int status) {
        ActivitySlot slot = new ActivitySlot();
        slot.setActivityId(activityId);
        slot.setProjectName("项目_" + System.nanoTime());
        slot.setStartTime(LocalDateTime.now());
        slot.setEndTime(LocalDateTime.now().plusHours(2));
        slot.setNeedCount(10);
        slotMapper.insert(slot);

        ActivityEnrollment e = new ActivityEnrollment();
        e.setActivityId(activityId);
        e.setSlotId(slot.getId());
        e.setVolunteerId(volunteerId);
        e.setStatus(status);
        e.setEnrollTime(LocalDateTime.now());
        enrollmentMapper.insert(e);
    }

    private Long insertVolunteer() {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime());
        v.setRealName("我的活动志愿者");
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }
}
