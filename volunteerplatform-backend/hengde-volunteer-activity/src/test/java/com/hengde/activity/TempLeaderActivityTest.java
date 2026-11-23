package com.hengde.activity;

import com.hengde.activity.dto.ActivityCreateDTO;
import com.hengde.activity.dto.ActivitySlotDTO;
import com.hengde.activity.dto.ProxyEnrollDTO;
import com.hengde.activity.service.ActivityService;
import com.hengde.activity.service.EnrollmentAdminService;
import com.hengde.activity.service.EnrollmentService;
import com.hengde.activity.service.RosterService;
import com.hengde.activity.vo.EnrollmentAdminVO;
import com.hengde.activity.vo.RosterVO;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.constant.Gender;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.VolunteerGroupMapper;
import com.hengde.organization.biz.dao.VolunteerGroupMemberMapper;
import com.hengde.organization.biz.entity.VolunteerGroup;
import com.hengde.organization.biz.entity.VolunteerGroupMember;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动临时负责人在活动域的两处消费（V4 临时负责人考试批）：
 * ① 报名开放时间——管理团队 / 临时负责人按活动给自己那一档设的更早时间报名，留空＝没有提前；代报名按被代的每个人各自的身份算；
 * ② 名单公示与后台报名列表——管理团队在前、考试通过的临时负责人其次（Row 13 F）。
 *
 * <p>资格行直接用 JDBC 造（有效 / 已到期 / 已撤销三种），「是不是临时负责人」的判定只看 organization 的 {@code TempLeaderQueryService}。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class TempLeaderActivityTest {

    private static final long ADMIN = 7L;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);

    @Autowired
    private ActivityService activityService;
    @Autowired
    private EnrollmentService enrollmentService;
    @Autowired
    private EnrollmentAdminService enrollmentAdminService;
    @Autowired
    private RosterService rosterService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private VolunteerGroupMapper groupMapper;
    @Autowired
    private VolunteerGroupMemberMapper groupMemberMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void enrollWindow_rolesGetTheirOwnEarlierWindow_blankMeansNoHeadStart() {
        LocalDateTime now = LocalDateTime.now();
        ActivityCreateDTO leaderEarly = base();
        leaderEarly.setEnrollOpenVolunteer(now.plusDays(1));
        leaderEarly.setEnrollOpenLeader(now.minusHours(1));
        Long aid = activityService.publish(leaderEarly, ADMIN);
        Long slot = firstSlot(aid);

        Long ordinary = volunteer(false);
        Long tempLeader = volunteer(false);
        qualification(tempLeader, null, false);
        Long expired = volunteer(false);
        qualification(expired, now.minusMinutes(1), false);
        Long revoked = volunteer(false);
        qualification(revoked, null, true);
        Long manager = volunteer(true);

        assertMessage("尚未开放报名", () -> enrollmentService.enroll(aid, List.of(slot), ordinary));
        assertEquals(1, enrollmentService.enroll(aid, List.of(slot), tempLeader), "临时负责人按自己那一档报");
        assertMessage("尚未开放报名", () -> enrollmentService.enroll(aid, List.of(slot), expired));
        assertMessage("尚未开放报名", () -> enrollmentService.enroll(aid, List.of(slot), revoked));
        assertMessage("尚未开放报名", () -> enrollmentService.enroll(aid, List.of(slot), manager));

        // 代报名：被代的每个人按自己的身份算，有一个没到就整批不报
        Long actor = volunteer(false);
        Long tempLeader2 = volunteer(false);
        qualification(tempLeader2, now.plusMonths(3), false);
        Long ordinary2 = volunteer(false);
        group(actor, List.of(tempLeader2, ordinary2));
        assertMessage("尚未开放报名", () -> enrollmentService.proxyEnroll(aid, proxy(List.of(tempLeader2, ordinary2), slot), actor));
        assertEquals(0, active(aid, tempLeader2), "整批不报");
        assertEquals(1, enrollmentService.proxyEnroll(aid, proxy(List.of(tempLeader2), slot), actor));

        ActivityCreateDTO managerEarly = base();
        managerEarly.setEnrollOpenVolunteer(now.plusDays(1));
        managerEarly.setEnrollOpenManager(now.minusHours(1));
        Long aid2 = activityService.publish(managerEarly, ADMIN);
        Long slot2 = firstSlot(aid2);
        assertEquals(1, enrollmentService.enroll(aid2, List.of(slot2), manager), "管理团队按自己那一档报");
        assertMessage("尚未开放报名", () -> enrollmentService.enroll(aid2, List.of(slot2), volunteer(false)));

        ActivityCreateDTO bothLater = base();
        bothLater.setEnrollOpenVolunteer(now.plusDays(2));
        bothLater.setEnrollOpenManager(now.plusDays(1));
        bothLater.setEnrollOpenLeader(now.plusDays(1));
        Long aid3 = activityService.publish(bothLater, ADMIN);
        Long tempLeader3 = volunteer(true);
        qualification(tempLeader3, null, false);
        assertMessage("尚未开放报名", () -> enrollmentService.enroll(aid3, List.of(firstSlot(aid3)), tempLeader3));

        Long aid4 = activityService.publish(base(), ADMIN);
        assertEquals(1, enrollmentService.enroll(aid4, List.of(firstSlot(aid4)), volunteer(false)), "志愿者时间留空＝即刻开放");
    }

    @Test
    void rosterAndAdminList_managersFirstThenTempLeaders() {
        Long aid = activityService.publish(base(), ADMIN);
        Long slot = firstSlot(aid);
        Long ordinary = volunteer(false);
        Long tempLeader = volunteer(false);
        qualification(tempLeader, null, false);
        Long manager = volunteer(true);
        Long expired = volunteer(false);
        qualification(expired, LocalDateTime.now().minusMinutes(1), false);
        for (Long v : List.of(ordinary, expired, tempLeader, manager)) {
            enrollmentService.enroll(aid, List.of(slot), v);
        }
        for (EnrollmentAdminVO e : enrollmentAdminService.list(aid, new PageQuery(), 0).getRecords()) {
            enrollmentAdminService.approve(e.getEnrollmentId(), ADMIN);
        }

        List<EnrollmentAdminVO> admin = enrollmentAdminService.list(aid, new PageQuery(), null).getRecords();
        // 后台列表只按报名时间（到秒）排，同一秒报的其余两人先后不定，只断言前两位与其余集合
        assertEquals(List.of(manager, tempLeader), admin.stream().limit(2).map(EnrollmentAdminVO::getVolunteerId).toList(),
                "管理团队在前、临时负责人其次");
        assertEquals(java.util.Set.of(ordinary, expired), admin.stream().skip(2).map(EnrollmentAdminVO::getVolunteerId)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(admin.get(0).isManager());
        assertTrue(admin.get(1).isTempLeader());
        assertFalse(admin.stream().filter(e -> e.getVolunteerId().equals(expired)).findFirst().orElseThrow().isTempLeader(), "到期的不算");

        RosterVO roster = rosterService.previewForAdmin(aid);
        List<RosterVO.Person> members = roster.getSlots().get(0).getMembers();
        assertEquals(4, members.size());
        assertTrue(members.get(0).isManager());
        assertTrue(members.get(1).isTempLeader());
        assertFalse(members.get(2).isTempLeader() || members.get(2).isManager());
        assertFalse(members.get(3).isTempLeader());
    }

    // ================= 造数 =================

    private ActivityCreateDTO base() {
        LocalDateTime start = LocalDateTime.now().plusDays(6).withHour(9).withMinute(0).withSecond(0).withNano(0)
                .plusMinutes(SEQ.incrementAndGet() % 50);
        ActivityCreateDTO t = new ActivityCreateDTO();
        t.setTitle("临时负责人批活动_" + SEQ.incrementAndGet());
        t.setLocation("雷州市西湖公园");
        t.setStartTime(start);
        t.setEndTime(start.plusHours(3));
        ActivitySlotDTO s = new ActivitySlotDTO();
        s.setProjectName("项目A");
        s.setStartTime(start);
        s.setEndTime(start.plusHours(2));
        s.setNeedCount(10);
        t.setSlots(List.of(s));
        return t;
    }

    private Long firstSlot(Long aid) {
        return jdbc.queryForObject("SELECT id FROM activity_slot WHERE activity_id = ? AND is_deleted = 0 ORDER BY start_time, id LIMIT 1",
                Long.class, aid);
    }

    private Long volunteer(boolean manager) {
        Volunteer v = new Volunteer();
        v.setOpenid("test:tl:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setRealName("临时负责人批志愿者" + SEQ.get());
        v.setGender(Gender.MALE);
        v.setBirthday(LocalDate.of(2000, 1, 1));
        v.setStatus(0);
        v.setManagerFlag(manager ? 1 : 0);
        v.setPhone(cryptoUtil.encrypt("137" + String.format("%08d", SEQ.get() % 100_000_000L)));
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private void qualification(Long volunteerId, LocalDateTime expire, boolean revoked) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO org_temp_leader_qualification (volunteer_id, source_type, granted_time, expire_time, revoked_by, "
                        + "revoked_time, revoke_reason, create_time) VALUES (?, 1, ?, ?, ?, ?, ?, ?)",
                volunteerId, now.minusDays(1), expire, revoked ? ADMIN : null, revoked ? now : null, revoked ? "用例撤销" : null, now);
    }

    private void group(Long leaderId, List<Long> members) {
        VolunteerGroup g = new VolunteerGroup();
        g.setGroupNo("G_" + System.nanoTime());
        g.setName("临时负责人批小组_" + SEQ.incrementAndGet());
        g.setLeaderId(leaderId);
        g.setStatus(1);
        groupMapper.insert(g);
        for (int i = -1; i < members.size(); i++) {
            VolunteerGroupMember m = new VolunteerGroupMember();
            m.setGroupId(g.getId());
            m.setVolunteerId(i < 0 ? leaderId : members.get(i));
            m.setRole(i < 0 ? 1 : 0);
            m.setStatus(1);
            m.setApplyTime(LocalDateTime.now());
            m.setAuditTime(LocalDateTime.now());
            groupMemberMapper.insert(m);
        }
    }

    private static ProxyEnrollDTO proxy(List<Long> targets, Long slot) {
        ProxyEnrollDTO p = new ProxyEnrollDTO();
        p.setVolunteerIds(targets);
        p.setSlotIds(List.of(slot));
        return p;
    }

    private long active(Long aid, Long volunteerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM activity_enrollment WHERE activity_id = ? AND volunteer_id = ? "
                + "AND status IN (0, 1) AND is_deleted = 0", Long.class, aid, volunteerId);
    }

    private static void assertMessage(String fragment, Executable call) {
        BusinessException e = assertThrows(BusinessException.class, call);
        assertTrue(e.getMessage().contains(fragment), "期望提示含「" + fragment + "」，实际：" + e.getMessage());
    }
}
