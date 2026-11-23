package com.hengde.activity;

import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dto.ActivityCreateDTO;
import com.hengde.activity.dto.ActivitySlotDTO;
import com.hengde.activity.dto.ActivityUpdateDTO;
import com.hengde.activity.dto.ProxyEnrollDTO;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityEnrollment;
import com.hengde.activity.service.ActivityLeaderService;
import com.hengde.activity.service.ActivityService;
import com.hengde.activity.service.EnrollmentAdminService;
import com.hengde.activity.service.EnrollmentService;
import com.hengde.activity.service.RosterService;
import com.hengde.activity.vo.RosterVO;
import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.AdminUser;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.constant.Gender;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.VolunteerGroupMapper;
import com.hengde.organization.biz.dao.VolunteerGroupMemberMapper;
import com.hengde.organization.biz.dao.VolunteerSquadMapper;
import com.hengde.organization.biz.entity.VolunteerGroup;
import com.hengde.organization.biz.entity.VolunteerGroupMember;
import com.hengde.organization.biz.entity.VolunteerSquad;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动补全批（V70）：指定分队报名（Row 69 起搁置的 {@code enroll_scope=1}，单个分队）+ 活动名单公示（Row 13）。
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。各用例自建活动与志愿者，名单查询只断言自己建的活动。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class ActivityRosterSquadTest {

    private static final long ADMIN = 7L;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);

    @Autowired
    private ActivityService activityService;
    @Autowired
    private EnrollmentService enrollmentService;
    @Autowired
    private EnrollmentAdminService enrollmentAdminService;
    @Autowired
    private ActivityLeaderService leaderService;
    @Autowired
    private RosterService rosterService;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ActivityEnrollmentMapper enrollmentMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private VolunteerSquadMapper squadMapper;
    @Autowired
    private VolunteerGroupMapper groupMapper;
    @Autowired
    private VolunteerGroupMemberMapper groupMemberMapper;
    @Autowired
    private AdminUserMapper adminUserMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

    // ======================= 指定分队 =======================

    @Test
    void squadScope_publishRules_andDetailsCarryTheSquadName() {
        Long squad = squad("雷州一中分队", 1);
        Long disabled = squad("停用分队", 0);

        assertMessage("请选择分队", () -> activityService.publish(scoped(null), ADMIN));
        assertMessage("不存在或已停用", () -> activityService.publish(scoped(disabled), ADMIN));
        assertMessage("不存在或已停用", () -> activityService.publish(scoped(Long.MAX_VALUE), ADMIN));
        ActivityCreateDTO wide = base();
        wide.setTargetSquadId(squad);
        assertMessage("不需要指定分队", () -> activityService.publish(wide, ADMIN));

        Long aid = activityService.publish(scoped(squad), ADMIN);
        Activity a = activityMapper.selectById(aid);
        assertEquals(1, a.getEnrollScope());
        assertEquals(squad, a.getTargetSquadId());
        assertEquals(nameOf(squad), activityService.detailForAdmin(aid).getTargetSquadName());
        assertEquals(nameOf(squad), activityService.detailForVolunteer(aid).getTargetSquadName());
        assertEquals(squad, activityService.detailForVolunteer(aid).getTargetSquadId());

        // 改回全平台：分队真的清掉（updateById 会跳过 null）
        ActivityUpdateDTO back = new ActivityUpdateDTO();
        org.springframework.beans.BeanUtils.copyProperties(base(), back);
        back.setEnrollScope(0);
        activityService.update(aid, back);
        assertNull(activityMapper.selectById(aid).getTargetSquadId());
        assertEquals(0, activityMapper.selectById(aid).getEnrollScope());
    }

    @Test
    void squadScope_onlyMembersEnroll_proxyTooButManualEnrollOverrides() {
        Long squad = squad("限定分队", 1);
        Long other = squad("别的分队", 1);
        Long aid = activityService.publish(scoped(squad), ADMIN);
        Long slot = firstSlot(aid);

        Long member = volunteer(squad, "13600000001", false);
        Long outsider = volunteer(other, "13600000002", false);
        Long noSquad = volunteer(null, "13600000003", false);

        assertEquals(1, enrollmentService.enroll(aid, List.of(slot), member));
        assertMessage("仅限「" + nameOf(squad) + "」的成员报名", () -> enrollmentService.enroll(aid, List.of(slot), outsider));
        assertMessage("仅限「" + nameOf(squad) + "」的成员报名", () -> enrollmentService.enroll(aid, List.of(slot), noSquad));

        // 同组代报名：被代报的人不在分队里照样拒绝，整批不落库
        Long actor = volunteer(squad, "13600000004", false);
        group(actor, List.of(outsider));
        ProxyEnrollDTO proxy = new ProxyEnrollDTO();
        proxy.setVolunteerIds(List.of(outsider));
        proxy.setSlotIds(List.of(slot));
        assertMessage("的成员报名", () -> enrollmentService.proxyEnroll(aid, proxy, actor));
        assertEquals(0, activeEnrollments(aid, outsider));

        // 后台补录越权：不看分队
        assertEquals(1, enrollmentAdminService.manualEnroll(aid, noSquad, List.of(slot), ADMIN));

        // 复制活动：分队跟着走
        Long copy = activityService.copy(aid, ADMIN);
        assertEquals(squad, activityMapper.selectById(copy).getTargetSquadId());
    }

    /**
     * 「报名」与「退出分队」必须串行：退队的事务还没提交时报名要等它，提交之后报名看到的是退队后的归属。
     * 用裸 JDBC 事务改掉 squad_id 不提交，确定性地摆出这个时序。
     */
    @Test
    void enrollWaitsForAnUncommittedSquadChange_andSeesItsResult() throws Exception {
        Long squad = squad("退队分队", 1);
        Long aid = activityService.publish(scoped(squad), ADMIN);
        Long slot = firstSlot(aid);
        Long vid = volunteer(squad, "13600000005", false);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE volunteer SET squad_id = NULL WHERE id = ?")) {
                ps.setLong(1, vid);
                ps.executeUpdate();
            }
            Future<Integer> f = pool.submit(() -> enrollmentService.enroll(aid, List.of(slot), vid));
            assertThrows(TimeoutException.class, () -> f.get(1500, TimeUnit.MILLISECONDS), "报名应当在等退队的事务");
            conn.commit();
            ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(30, TimeUnit.SECONDS));
            assertInstanceOf(BusinessException.class, e.getCause());
            assertTrue(e.getCause().getMessage().contains("的成员报名"), e.getCause().getMessage());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, activeEnrollments(aid, vid));
    }

    /**
     * 同样的时序换成<b>代报名</b>——这一条才真正区分「资格档案是当前读」还是快照读：代报名先跑同组校验（普通读），
     * RR 的读视图在那一刻就定了；之后处置闸门在被代报人的志愿者行上等退队提交，等到之后若资格档案仍是快照读，
     * 读到的还是退队前的分队，就会放行（变异验证：把 {@code getProfileForEligibilityForShare} 改回普通读，只有这条红，
     * 上面那条自助报名的照样绿——自助报名在闸门之前没有普通读，读视图建立得晚）。
     */
    @Test
    void proxyEnrollWaitsForAnUncommittedSquadChange_andSeesItsResult() throws Exception {
        Long squad = squad("代报退队分队", 1);
        Long aid = activityService.publish(scoped(squad), ADMIN);
        Long slot = firstSlot(aid);
        Long actor = volunteer(squad, "13600000006", false);
        Long target = volunteer(squad, "13600000007", false);
        group(actor, List.of(target));
        ProxyEnrollDTO proxy = new ProxyEnrollDTO();
        proxy.setVolunteerIds(List.of(target));
        proxy.setSlotIds(List.of(slot));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE volunteer SET squad_id = NULL WHERE id = ?")) {
                ps.setLong(1, target);
                ps.executeUpdate();
            }
            Future<Integer> f = pool.submit(() -> enrollmentService.proxyEnroll(aid, proxy, actor));
            assertThrows(TimeoutException.class, () -> f.get(1500, TimeUnit.MILLISECONDS), "代报名应当在等退队的事务");
            conn.commit();
            ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(30, TimeUnit.SECONDS));
            assertInstanceOf(BusinessException.class, e.getCause());
            assertTrue(e.getCause().getMessage().contains("的成员报名"), e.getCause().getMessage());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, activeEnrollments(aid, target));
    }

    // ======================= 名单公示 =======================

    @Test
    void roster_publishWindow() {
        Long aid = activityService.publish(base(), ADMIN);
        assertMessage("没有正在公示", () -> rosterService.detailForVolunteer(aid, true));
        assertFalse(listed(aid), "没确认名单不公示");

        rosterService.publish(aid, ADMIN);
        assertNotNull(activityMapper.selectById(aid).getRosterPublishTime());
        // 挪到一小时前再点一次：同一秒内点两次时间本来就相等，证明不了「不刷新」
        jdbc.update("UPDATE activity SET roster_publish_time = ? WHERE id = ?", LocalDateTime.now().minusHours(1).withNano(0), aid);
        LocalDateTime first = activityMapper.selectById(aid).getRosterPublishTime();
        assertEquals(ADMIN, activityMapper.selectById(aid).getRosterPublishBy());
        rosterService.publish(aid, ADMIN + 1);
        assertEquals(first, activityMapper.selectById(aid).getRosterPublishTime(), "再点不刷新公示时间");
        assertEquals(aid, rosterService.detailForVolunteer(aid, true).getActivityId());
        assertTrue(listed(aid));
        Long copied = activityService.copy(aid, ADMIN);
        assertNull(activityMapper.selectById(copied).getRosterPublishTime(), "复制出来的活动还没有名单，不继承公示");
        assertFalse(listed(copied));

        rosterService.withdraw(aid);
        assertFalse(listed(aid), "撤回后不公示");
        rosterService.withdraw(aid);
        rosterService.publish(aid, ADMIN);
        assertTrue(listed(aid));

        // 负责人提前点了开始：不再公示；也不能再确认
        jdbc.update("UPDATE activity SET run_status = 1 WHERE id = ?", aid);
        assertFalse(listed(aid), "活动开始后公示自动消失");
        assertMessage("没有正在公示", () -> rosterService.detailForVolunteer(aid, true));
        jdbc.update("UPDATE activity SET run_status = 0 WHERE id = ?", aid);
        assertTrue(listed(aid));

        // 到了开始时间：同样消失
        jdbc.update("UPDATE activity SET start_time = ? WHERE id = ?", LocalDateTime.now().minusMinutes(1), aid);
        assertFalse(listed(aid));
        jdbc.update("UPDATE activity SET start_time = ? WHERE id = ?", LocalDateTime.now().plusDays(3), aid);

        // 取消的活动：消失
        activityService.cancel(aid, "测试取消");
        assertFalse(listed(aid));

        Long started = activityService.publish(base(), ADMIN);
        jdbc.update("UPDATE activity SET run_status = 1 WHERE id = ?", started);
        assertMessage("还没开始", () -> rosterService.publish(started, ADMIN));
        assertMessage("活动不存在", () -> rosterService.publish(Long.MAX_VALUE, ADMIN));
    }

    @Test
    void roster_content_slotsOrderPhonesAndLeaders() {
        ActivityCreateDTO dto = base();
        dto.setNeedAudit(1);
        LocalDateTime start = dto.getStartTime();
        ActivitySlotDTO second = slot("下午场", start.plusHours(4), start.plusHours(6));
        dto.setSlots(List.of(slot("上午场", start, start.plusHours(2)), second));
        dto.setEndTime(start.plusHours(6));
        Long aid = activityService.publish(dto, ADMIN);
        List<Long> slots = slotIds(aid);
        Long morning = slots.get(0);
        Long afternoon = slots.get(1);

        Long early = volunteer(null, "13711110001", false);
        Long manager = volunteer(null, "13711110002", true);
        Long leader = volunteer(null, "13711110003", false);
        Long pending = volunteer(null, "13711110004", false);
        Long rejected = volunteer(null, "13711110005", false);
        for (Long v : List.of(early, manager, leader, pending, rejected)) {
            enrollmentService.enroll(aid, List.of(morning), v);
        }
        enrollmentService.enroll(aid, List.of(afternoon), volunteer(null, "13711110006", false));
        approve(aid, early);
        approve(aid, manager);
        approve(aid, leader);
        enrollmentAdminService.reject(enrollmentOf(aid, rejected), "不符合", ADMIN);
        leaderService.assign(aid, 1, leader, ADMIN);
        Long adminLeader = admin("现场督导", "13899990000");
        leaderService.assign(aid, 2, adminLeader, ADMIN);
        rosterService.publish(aid, ADMIN);

        RosterVO seen = rosterService.detailForVolunteer(aid, true);
        assertEquals(2, seen.getSlots().size());
        assertEquals("上午场", seen.getSlots().get(0).getProjectName(), "时间段按开始时间");
        List<RosterVO.Person> am = seen.getSlots().get(0).getMembers();
        assertEquals(3, am.size(), "只列已通过的：" + am);
        assertTrue(am.get(0).isManager(), "管理团队在前");
        assertEquals("137****0002", am.get(0).getPhone());
        assertEquals("137****0001", am.get(1).getPhone(), "其余按报名先后");
        RosterVO.Person leaderRow = am.get(2);
        assertTrue(leaderRow.isLeader());
        assertEquals("13711110003", leaderRow.getPhone(), "负责人全显示");
        assertEquals(0, seen.getSlots().get(1).getMembers().size(), "下午场的报名还没审");

        assertEquals(2, seen.getLeaders().size());
        assertEquals("13711110003", seen.getLeaders().get(0).getPhone());
        assertEquals("现场督导", seen.getLeaders().get(1).getName());
        assertEquals("13899990000", seen.getLeaders().get(1).getPhone());

        RosterVO guest = rosterService.detailForVolunteer(aid, false);
        assertEquals("137****0003", guest.getSlots().get(0).getMembers().get(2).getPhone(), "游客看负责人也打 *");
        assertTrue(guest.getLeaders().stream().allMatch(p -> p.getPhone().contains("****")));

        RosterVO preview = rosterService.previewForAdmin(aid);
        assertTrue(preview.getSlots().get(0).getMembers().stream().noneMatch(p -> p.getPhone().contains("*")), "后台预览全显示");

        // 名单是实时的：确认之后再删掉一个人，公示上即时没有
        enrollmentAdminService.delete(enrollmentOf(aid, early));
        assertEquals(2, rosterService.detailForVolunteer(aid, true).getSlots().get(0).getMembers().size());
    }

    // ======================= helpers =======================

    private boolean listed(Long aid) {
        PageQuery q = new PageQuery();
        q.setPage(1);
        q.setSize(100);
        return rosterService.listForVolunteer(q, true).getRecords().stream().anyMatch(r -> r.getActivityId().equals(aid));
    }

    private ActivityCreateDTO base() {
        LocalDateTime start = LocalDateTime.now().plusDays(5).withHour(9).withMinute(0).withSecond(0).withNano(0);
        ActivityCreateDTO t = new ActivityCreateDTO();
        t.setTitle("补全批活动_" + SEQ.incrementAndGet());
        t.setLocation("雷州市西湖公园");
        t.setStartTime(start);
        t.setEndTime(start.plusHours(3));
        t.setSlots(List.of(slot("项目A", start, start.plusHours(2))));
        return t;
    }

    private ActivityCreateDTO scoped(Long squadId) {
        ActivityCreateDTO t = base();
        t.setEnrollScope(1);
        t.setTargetSquadId(squadId);
        return t;
    }

    private static ActivitySlotDTO slot(String name, LocalDateTime start, LocalDateTime end) {
        ActivitySlotDTO s = new ActivitySlotDTO();
        s.setProjectName(name);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setNeedCount(10);
        return s;
    }

    private Long firstSlot(Long aid) {
        return slotIds(aid).get(0);
    }

    private List<Long> slotIds(Long aid) {
        return jdbc.queryForList("SELECT id FROM activity_slot WHERE activity_id = ? AND is_deleted = 0 ORDER BY start_time, id",
                Long.class, aid);
    }

    private Long squad(String name, int status) {
        VolunteerSquad s = new VolunteerSquad();
        s.setName(name + "-" + SEQ.incrementAndGet());
        s.setType("学校");
        s.setMemberLimit(0);
        s.setStatus(status);
        squadMapper.insert(s);
        return s.getId();
    }

    private String nameOf(Long squadId) {
        return squadMapper.selectById(squadId).getName();
    }

    private Long volunteer(Long squadId, String phone, boolean manager) {
        Volunteer v = new Volunteer();
        v.setOpenid("test:roster:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setRealName("名单志愿者" + SEQ.get());
        v.setGender(Gender.MALE);
        v.setBirthday(LocalDate.of(2000, 1, 1));
        v.setStatus(0);
        v.setManagerFlag(manager ? 1 : 0);
        v.setSquadId(squadId);
        v.setPhone(cryptoUtil.encrypt(phone));
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private Long admin(String realName, String phone) {
        AdminUser u = new AdminUser();
        u.setUsername("roster_admin_" + SEQ.incrementAndGet());
        u.setPassword("x");
        u.setRealName(realName);
        u.setPhone(phone);
        u.setIsSuperAdmin(0);
        u.setStatus(0);
        adminUserMapper.insert(u);
        return u.getId();
    }

    private void group(Long leaderId, List<Long> members) {
        VolunteerGroup g = new VolunteerGroup();
        g.setGroupNo("G_" + System.nanoTime());
        g.setName("名单小组_" + SEQ.incrementAndGet());
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

    private void approve(Long aid, Long volunteerId) {
        enrollmentAdminService.approve(enrollmentOf(aid, volunteerId), ADMIN);
    }

    private Long enrollmentOf(Long aid, Long volunteerId) {
        return enrollmentMapper.selectList(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, aid)
                .eq(ActivityEnrollment::getVolunteerId, volunteerId)
                .orderByDesc(ActivityEnrollment::getId)).get(0).getId();
    }

    private long activeEnrollments(Long aid, Long volunteerId) {
        return enrollmentMapper.selectCount(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, aid)
                .eq(ActivityEnrollment::getVolunteerId, volunteerId)
                .in(ActivityEnrollment::getStatus, 0, 1));
    }

    private static void assertMessage(String fragment, Executable call) {
        BusinessException e = assertThrows(BusinessException.class, call);
        assertTrue(e.getMessage().contains(fragment), "期望提示含「" + fragment + "」，实际：" + e.getMessage());
    }
}
