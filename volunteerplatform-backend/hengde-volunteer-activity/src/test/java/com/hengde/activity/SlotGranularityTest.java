package com.hengde.activity;

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
import com.hengde.activity.service.ActivityRankingQueryService;
import com.hengde.activity.service.ActivityStatsService;
import com.hengde.activity.service.AttendanceService;
import com.hengde.activity.service.ServiceRecordService;
import com.hengde.activity.vo.AttendanceRosterVO;
import com.hengde.activity.vo.ManagedActivityDetailVO;
import com.hengde.activity.vo.RankingRowView;
import com.hengde.activity.vo.ServiceRecordVO;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V30「场次是参与的最小单元」在<b>读模型</b>上的验证。
 *
 * <p>写侧（签到/签退按场次）已由 {@link ActivityAttendanceServiceTest} 覆盖，但那些用例里
 * 每个活动只有一个场次，因此<b>「同一人在同一活动占两行」这个新形态从没被读出来过</b>——
 * 名单、排行、服务记录三处按 volunteerId 聚合的旧代码可以全绿通过。本类专门造多场次数据。</p>
 *
 * <p>需求来源：xlsx Row 32 C（负责人页「显示活动场次…活动时间段…是否到位…是否违规」）、
 * 原型 P15（报名详情同一人多行、可筛选时间段）、P97（服务记录逐条对应一次签到签退）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class SlotGranularityTest {

    private static final BigDecimal ACT_LAT = new BigDecimal("21.2707");
    private static final BigDecimal ACT_LNG = new BigDecimal("110.0973");

    @Autowired
    private AttendanceService attendanceService;
    @Autowired
    private ActivityRankingQueryService rankingQueryService;
    @Autowired
    private ActivityStatsService statsService;
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

    // ---------- 1. 负责人名单：同一志愿者两场 ----------

    /**
     * 一人报了上午 + 下午两场，负责人名单必须出<b>两行</b>，各带各的签到签退与违规数。
     *
     * <p>旧实现按 {@code distinct(volunteerId)} 出一行、考勤按 volunteerId 索引，
     * 会静默丢掉一场并把整个活动的违规数安到两场头上。</p>
     */
    @Test
    void roster_sameVolunteerTwoSlots_twoRowsEachWithOwnAttendanceAndViolation() {
        // 两场都必须落在签到窗内（开始前 2h 开放、结束后 2h 截止），否则测的是时间窗而不是场次粒度
        LocalDateTime base = LocalDateTime.now().minusHours(2);
        Long aid = insertActivity(base, base.plusHours(3));
        Long morning = insertSlot(aid, base, base.plusHours(1));                    // now-2h ~ now-1h
        Long afternoon = insertSlot(aid, base.plusHours(1), base.plusHours(3));     // now-1h ~ now+1h
        Long vid = insertVolunteer();
        approve(aid, morning, vid);
        approve(aid, afternoon, vid);

        // 只在上午场签到；下午场未签到
        attendanceService.startActivity(aid, 100L);
        attendanceService.checkIn(aid, morning, vid, ACT_LAT, ACT_LNG, 2);
        // 只在下午场记一条违规
        attendanceService.recordViolation(aid, afternoon, vid, 1, "玩手机", 100L);

        ManagedActivityDetailVO detail = attendanceService.leaderDetail(aid);
        List<AttendanceRosterVO> roster = detail.getRoster();

        assertEquals(2, roster.size(), "一人报两场 → 名单两行");
        AttendanceRosterVO first = roster.get(0);
        AttendanceRosterVO second = roster.get(1);
        assertNotEquals(first.getSlotId(), second.getSlotId(), "两行必须是不同场次");
        // 按场次开始时间排序，上午场在前
        assertEquals(morning, first.getSlotId());
        assertEquals(afternoon, second.getSlotId());
        assertNotNull(first.getSlotStartTime(), "行上要带岗位时间，否则负责人分不清哪一场");
        assertNotNull(first.getSlotProjectName());

        // 签到只落在上午场
        assertNotNull(first.getCheckInTime(), "上午场已签到");
        assertEquals(null, second.getCheckInTime(), "下午场未签到，不能把上午场的签到显示过来");

        // 违规只算下午场那一条
        assertEquals(0, first.getViolationCount(), "上午场没违规，不该显示整个活动的违规数");
        assertEquals(1, second.getViolationCount(), "下午场 1 条违规");
    }

    // ---------- 2. 活动次数榜：同活动多场只算一次 ----------

    /**
     * 「活动次数」按<b>活动</b>去重，「人次」按<b>场次</b>计——两者 V30 后不再相等，各自都要对。
     *
     * <p>V30 删掉了 {@code uk_activity_volunteer}，而榜单原来的 {@code COUNT(*)} 正是靠那个唯一键
     * 才等价于去重计数；键一没，榜单会不动一行代码就变成「场次数榜」，
     * 与志愿者资料页的 {@code activityCount}、报名门槛「已参加活动次数」全部对不上。</p>
     */
    @Test
    void ranking_sameActivityTwoSlots_countsActivityOnce_butParticipationsTwice() {
        // 两场都必须落在签到窗内（开始前 2h 开放、结束后 2h 截止），否则测的是时间窗而不是场次粒度
        LocalDateTime base = LocalDateTime.now().minusHours(2);
        Long aid = insertActivity(base, base.plusHours(3));
        Long morning = insertSlot(aid, base, base.plusHours(1));                    // now-2h ~ now-1h
        Long afternoon = insertSlot(aid, base.plusHours(1), base.plusHours(3));     // now-1h ~ now+1h
        Long vid = insertVolunteer();
        approve(aid, morning, vid);
        approve(aid, afternoon, vid);

        long participationsBefore = statsService.countParticipations();

        attendanceService.startActivity(aid, 100L);
        attendanceService.checkIn(aid, morning, vid, ACT_LAT, ACT_LNG, 2);
        attendanceService.checkIn(aid, afternoon, vid, ACT_LAT, ACT_LNG, 2);

        // 两条考勤行确实建出来了（否则下面的断言会因为「根本没有两行」而假通过）
        assertEquals(2, attendanceMapper.selectCount(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<ActivityAttendance>lambdaQuery()
                                .eq(ActivityAttendance::getActivityId, aid)
                                .eq(ActivityAttendance::getVolunteerId, vid)),
                "V30：一人两场 = 两条考勤");

        // 人次 +2（原型 P97：一条服务记录 = 一次签到签退 = 一个场次）
        assertEquals(participationsBefore + 2, statsService.countParticipations(),
                "人次按场次计");

        // 活动次数榜：同一活动的两场只算 1 次
        List<RankingRowView> rows = rankingQueryService.topByAttendanceCount(null, null, 50);
        RankingRowView mine = rows.stream().filter(r -> vid.equals(r.getVolunteerId())).findFirst().orElse(null);
        assertNotNull(mine, "该志愿者应在活动次数榜上");
        assertEquals(1L, mine.getMetricValue(),
                "同一活动报两场并各自签到，活动次数仍是 1——与资料页 activityCount、报名门槛同口径");

        // 勋章「获取进度」必须与榜单同数：totalAttendanceCount 的 javadoc 自己写着
        // 「否则同一个人在排行榜上是 3 次、在勋章进度里是 5 次」——只改榜单不改它，正好造出这个偏差。
        assertEquals(mine.getMetricValue(), rankingQueryService.totalAttendanceCount(vid),
                "勋章进度与活动次数榜必须逐字同口径");
    }

    // ---------- 3. 服务记录：同活动两条能区分 ----------

    /** 服务记录一行一场次，必须带得出 slotId 与岗位时间，否则界面上两行长得一模一样。 */
    @Test
    void serviceRecords_sameActivityTwoSlots_carrySlotIdentity() {
        // 两场都必须落在签到窗内（开始前 2h 开放、结束后 2h 截止），否则测的是时间窗而不是场次粒度
        LocalDateTime base = LocalDateTime.now().minusHours(2);
        Long aid = insertActivity(base, base.plusHours(3));
        Long morning = insertSlot(aid, base, base.plusHours(1));                    // now-2h ~ now-1h
        Long afternoon = insertSlot(aid, base.plusHours(1), base.plusHours(3));     // now-1h ~ now+1h
        Long vid = insertVolunteer();
        approve(aid, morning, vid);
        approve(aid, afternoon, vid);

        attendanceService.startActivity(aid, 100L);
        attendanceService.checkIn(aid, morning, vid, ACT_LAT, ACT_LNG, 2);
        attendanceService.checkIn(aid, afternoon, vid, ACT_LAT, ACT_LNG, 2);

        PageResult<ServiceRecordVO> page = serviceRecordService.myRecords(new PageQuery(), vid);
        List<ServiceRecordVO> rows = page.getRecords();
        assertEquals(2, rows.size(), "两场 = 两条服务记录");
        assertTrue(rows.stream().allMatch(r -> r.getSlotId() != null), "每条都要带 slotId");
        assertTrue(rows.stream().allMatch(r -> r.getSlotStartTime() != null), "每条都要带岗位时间");
        assertEquals(2, rows.stream().map(ServiceRecordVO::getSlotId).distinct().count(),
                "两条的场次必须不同——否则说明读的是同一行");
    }

    // ---------- 夹具 ----------

    private Long insertActivity(LocalDateTime start, LocalDateTime end) {
        Activity a = new Activity();
        a.setTitle("多场次测试活动_" + System.nanoTime());
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

    private void approve(Long activityId, Long slotId, Long volunteerId) {
        ActivityEnrollment e = new ActivityEnrollment();
        e.setActivityId(activityId);
        e.setSlotId(slotId);
        e.setVolunteerId(volunteerId);
        e.setStatus(1);
        e.setEnrollTime(LocalDateTime.now());
        enrollmentMapper.insert(e);
    }

    private Long insertVolunteer() {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime());
        v.setRealName("多场次志愿者");
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }
}
