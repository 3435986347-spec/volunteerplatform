package com.hengde.organization.biz;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.SquadApplicationMapper;
import com.hengde.organization.biz.dto.SquadDTO;
import com.hengde.organization.biz.dao.VolunteerSquadMapper;
import com.hengde.organization.biz.entity.SquadApplication;
import com.hengde.organization.biz.entity.VolunteerSquad;
import com.hengde.organization.biz.service.SquadService;
import com.hengde.organization.biz.vo.SquadApplicationVO;
import com.hengde.organization.biz.vo.SquadVO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分队负责人在小程序里审核加入申请（协会 2026-09-02：分队加入申请「分队自己审，也可以由部门审核通过」；
 * 需求表 Row 60「由上述分队进行审核是否通过」）。
 *
 * <p>要钉住的：只有这个分队的负责人能审、分队停用了不能审、不能审自己的申请；
 * 审核人与审核人类型都记下来（负责人是 volunteer.id、部门是 admin_user.id，不带类型分不清是谁）；
 * 负责人与部门同时审同一条只成一个。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class SquadLeaderReviewTest {

    private static final long ADMIN = 700L;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 100_000_000L);

    @Autowired
    private SquadService squadService;
    @Autowired
    private VolunteerSquadMapper squadMapper;
    @Autowired
    private SquadApplicationMapper applicationMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

    @Test
    void leaderReviewsOnlyTheirOwnEnabledSquad_andTheAuditorIsRecorded() {
        Long leader = volunteer();
        Long otherLeader = volunteer();
        Long outsider = volunteer();
        Long squad = squad(leader, 1);
        Long otherSquad = squad(otherLeader, 1);
        Long disabledSquad = squad(leader, 0);

        Long a1 = application(squad, volunteer());
        Long a2 = application(squad, volunteer());
        Long other = application(otherSquad, volunteer());

        // 我负责的分队：只列启用中的
        List<SquadVO> led = squadService.ledSquads(leader);
        assertEquals(List.of(squad), led.stream().map(SquadVO::getId).toList(), "停用的分队不列出");

        // 列表只看得到自己负责的分队、默认只看待审
        List<Long> pending = squadService.applicationsForLeader(leader, squad, null, page()).getRecords()
                .stream().map(SquadApplicationVO::getId).toList();
        assertTrue(pending.containsAll(List.of(a1, a2)) && !pending.contains(other));
        assertMessage("只有分队负责人可以审核本分队的加入申请",
                () -> squadService.applicationsForLeader(outsider, squad, null, page()));
        assertMessage("只有分队负责人可以审核本分队的加入申请",
                () -> squadService.applicationsForLeader(leader, otherSquad, null, page()));

        // 批准：归属写上、审核人是负责人（类型 2）
        squadService.approveByLeader(leader, a1);
        SquadApplication approved = applicationMapper.selectById(a1);
        assertEquals(1, approved.getStatus());
        assertEquals(leader, approved.getAuditBy());
        assertEquals(SquadService.AUDITOR_SQUAD_LEADER, approved.getAuditByType());
        assertEquals(squad, volunteerMapper.selectById(approved.getVolunteerId()).getSquadId());

        // 拒绝：原因与审核人
        squadService.rejectByLeader(leader, a2, "名额已满");
        SquadApplication rejected = applicationMapper.selectById(a2);
        assertEquals(2, rejected.getStatus());
        assertEquals("名额已满", rejected.getRejectReason());
        assertEquals(SquadService.AUDITOR_SQUAD_LEADER, rejected.getAuditByType());
        assertEquals(1, squadService.applicationsForLeader(leader, squad, 2, page()).getRecords().size());

        // 别的分队的、停用分队的，一律审不了
        assertMessage("只有分队负责人可以审核本分队的加入申请", () -> squadService.approveByLeader(leader, other));
        assertMessage("只有分队负责人可以审核本分队的加入申请", () -> squadService.rejectByLeader(outsider, other, null));
        Long inDisabled = application(disabledSquad, volunteer());
        assertMessage("只有分队负责人可以审核本分队的加入申请", () -> squadService.approveByLeader(leader, inDisabled));
        assertEquals(0, applicationMapper.selectById(other).getStatus(), "被拒的操作什么也没改");

        // 部门审核：审核人是后台账号（类型 1）
        squadService.approveApplication(other, ADMIN);
        SquadApplication byAdmin = applicationMapper.selectById(other);
        assertEquals(ADMIN, byAdmin.getAuditBy());
        assertEquals(SquadService.AUDITOR_ADMIN, byAdmin.getAuditByType());
    }

    @Test
    void leaderCannotReviewTheirOwnApplication() {
        Long leader = volunteer();
        Long squad = squad(leader, 1);
        Long own = application(squad, leader);
        assertMessage("不能审核自己的申请", () -> squadService.approveByLeader(leader, own));
        assertMessage("不能审核自己的申请", () -> squadService.rejectByLeader(leader, own, "x"));
        SquadApplication row = applicationMapper.selectById(own);
        assertEquals(0, row.getStatus());
        assertNull(row.getAuditBy());
        assertNull(volunteerMapper.selectById(leader).getSquadId());
    }

    /**
     * 后台指定负责人：只收已实名且账号正常的志愿者；取消负责人要真的清掉——
     * {@code updateById} 跳过 null，不显式 set 的话「取消」静默无效、那个人照样能审。
     */
    @Test
    void leaderChosenInTheConsole_mustBeEligible_andClearingActuallyRevokes() {
        Long leader = volunteer();
        Long guest = volunteer();
        volunteerMapper.update(null, Wrappers.<Volunteer>lambdaUpdate()
                .eq(Volunteer::getId, guest).set(Volunteer::getRegisterTime, null));
        Long disabled = volunteer();
        Volunteer d = volunteerMapper.selectById(disabled);
        d.setStatus(1);
        volunteerMapper.updateById(d);

        assertMessage("分队负责人须是已实名且账号正常的志愿者", () -> squadService.create(dto(guest)));
        assertMessage("分队负责人须是已实名且账号正常的志愿者", () -> squadService.create(dto(disabled)));

        Long squad = squadService.create(dto(leader));
        SquadVO listed = squadService.list(page(), true).getRecords().stream()
                .filter(v -> v.getId().equals(squad)).findFirst().orElseThrow();
        assertEquals(volunteerMapper.selectById(leader).getRealName(), listed.getLeaderVolunteerName(),
                "后台列表要看得出审核权给了谁，联系人文字可以不是同一个人");
        assertEquals("联系人", listed.getLeaderName());
        assertNull(squadService.list(page(), false).getRecords().stream()
                .filter(v -> v.getId().equals(squad)).findFirst().orElseThrow().getLeaderVolunteerName(),
                "志愿者端不下发");

        Long app = application(squad, volunteer());
        assertEquals(1, squadService.applicationsForLeader(leader, squad, null, page()).getRecords().size());

        // 原负责人后来被停用：改分队名照样能改（只校验换上来的人）
        Volunteer l = volunteerMapper.selectById(leader);
        l.setStatus(1);
        volunteerMapper.updateById(l);
        SquadDTO rename = dto(leader);
        rename.setName("改了名字_" + SEQ.incrementAndGet());
        squadService.update(squad, rename);
        l.setStatus(0);
        volunteerMapper.updateById(l);

        // 换成不合格的人：拒绝，原负责人不变
        assertMessage("分队负责人须是已实名且账号正常的志愿者", () -> squadService.update(squad, dto(disabled)));
        assertEquals(leader, squadMapper.selectById(squad).getLeaderId());

        // 取消负责人：真的清掉，原负责人审不了了
        SquadDTO clear = dto(null);
        clear.setLeaderName(null);
        squadService.update(squad, clear);
        VolunteerSquad after = squadMapper.selectById(squad);
        assertNull(after.getLeaderId());
        assertNull(after.getLeaderName(), "联系人文字清空也要生效");
        assertMessage("只有分队负责人可以审核本分队的加入申请", () -> squadService.approveByLeader(leader, app));
        assertEquals(0, applicationMapper.selectById(app).getStatus());
    }

    private static SquadDTO dto(Long leaderId) {
        SquadDTO dto = new SquadDTO();
        dto.setName("后台指定负责人_" + SEQ.incrementAndGet());
        dto.setType("学校分队");
        dto.setLeaderId(leaderId);
        dto.setLeaderName("联系人");
        dto.setMemberLimit(0);
        dto.setStatus(1);
        return dto;
    }

    /**
     * 人数上限不能被两条审核路并发越过：部门在后台批一份、分队负责人在小程序批另一份，各自数一数都没到上限，
     * 一起写进去就超员了。批准在事务第一条锁住分队行，同一分队的批准排成一队。
     *
     * <p><b>确定性构造</b>，不靠调度撞：裸 JDBC 事务模拟「另一条路正在批」——锁住分队行、写了归属、还没提交；
     * 这时负责人批准必须<b>等着</b>（1 秒内拿不到结果），对方提交之后它要看得见那条归属、报「已达上限」。
     * 去掉那把锁的话，负责人批准不会等、数到的是提交前的人数，直接成功，分队超员。</p>
     */
    @Test
    void approvalsOfTheSameSquadAreSerialized_soTheMemberLimitHolds() throws Exception {
        Long leader = volunteer();
        Long squad = squad(leader, 1);
        jdbc.update("UPDATE volunteer_squad SET member_limit = 1 WHERE id = ?", squad);
        Long inFlight = volunteer();
        Long app = application(squad, volunteer());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var lock = c.prepareStatement("SELECT id FROM volunteer_squad WHERE id = ? FOR UPDATE");
                 var join = c.prepareStatement("UPDATE volunteer SET squad_id = ? WHERE id = ?")) {
                lock.setLong(1, squad);
                lock.executeQuery().close();
                join.setLong(1, squad);
                join.setLong(2, inFlight);
                join.executeUpdate();
            }
            Future<Throwable> leaderApproves = pool.submit(() -> {
                try {
                    squadService.approveByLeader(leader, app);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            assertThrows(TimeoutException.class,
                    () -> leaderApproves.get(1, TimeUnit.SECONDS),
                    "另一条路还没提交时，负责人的批准必须等着");
            c.commit();

            Throwable outcome = leaderApproves.get(15, TimeUnit.SECONDS);
            assertTrue(outcome instanceof BusinessException, "应被上限挡下：" + outcome);
            assertEquals("分队人数已达上限", outcome.getMessage());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1L, volunteerMapper.selectCount(Wrappers.<Volunteer>lambdaQuery().eq(Volunteer::getSquadId, squad)),
                "分队没有超员");
        assertEquals(0, applicationMapper.selectById(app).getStatus(), "被挡下的申请仍是待审");
    }

    /**
     * 后台正在撤换负责人 / 停用分队时，原负责人在途的<b>拒绝</b>不能抢在前面落库（小程序侧 2026-10-17 交付材料提的竞争窗口）。
     *
     * <p>同上，确定性构造：裸 JDBC 事务把分队行改成「负责人换人」或「停用」、还没提交；这时原负责人点拒绝必须<b>等着</b>，
     * 对方提交之后它要看得见、报「只有分队负责人…」，申请仍是待审。拒绝若不锁分队行：负责人检查读的是快照、
     * 申请行上又没有冲突，它会直接成功——审核人记成一个此刻已经不是负责人（或分队已停用）的人。</p>
     */
    @Test
    void leaderRejectWaitsForAnInFlightLeaderChangeOrDisable_andThenIsRefused() throws Exception {
        Long otherLeader = volunteer();
        for (String change : List.of("leader_id = " + otherLeader, "leader_id = NULL", "status = 0")) {
            Long leader = volunteer();
            Long squad = squad(leader, 1);
            Long app = application(squad, volunteer());

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (var update = c.prepareStatement("UPDATE volunteer_squad SET " + change + " WHERE id = ?")) {
                    update.setLong(1, squad);
                    assertEquals(1, update.executeUpdate());
                }
                Future<Throwable> leaderRejects = pool.submit(() -> {
                    try {
                        squadService.rejectByLeader(leader, app, "名额已满");
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                });
                assertThrows(TimeoutException.class,
                        () -> leaderRejects.get(1, TimeUnit.SECONDS),
                        change + "：撤换 / 停用还没提交时，原负责人的拒绝必须等着");
                c.commit();

                Throwable outcome = leaderRejects.get(15, TimeUnit.SECONDS);
                assertTrue(outcome instanceof BusinessException, change + "：应被挡下：" + outcome);
                assertEquals("只有分队负责人可以审核本分队的加入申请", outcome.getMessage());
            } finally {
                pool.shutdownNow();
            }
            SquadApplication row = applicationMapper.selectById(app);
            assertEquals(0, row.getStatus(), change + "：被挡下的申请仍是待审");
            assertNull(row.getAuditBy(), change + "：没有记下审核人");
        }
    }

    /**
     * 压测（{@code @Tag("stress")}）：负责人与部门乱序批准 / 拒绝，同时后台不停地换负责人、停用又启用分队。
     *
     * <p>断言：除业务拒绝外零异常（拒绝也锁分队行之后，死锁、锁超时都算失败）；每个分队不超员；
     * 归属与「已通过」一一对应；报成功的负责人审核恰好就是落库的审核人；负责人拒绝的「成功」与「已不是负责人」两种结局都出现过。</p>
     */
    @Tag("stress")
    @Test
    void reviewChurnWithLeaderChangesAndDisables_keepsMembershipAndAuditSound() throws Exception {
        int limit = 6;
        List<Long> squads = new ArrayList<>();
        List<Long> people = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Long leader = volunteer();
            people.add(leader);
            squads.add(squad(leader, 1));
            people.add(volunteer());
        }
        for (Long s : squads) {
            jdbc.update("UPDATE volunteer_squad SET member_limit = ? WHERE id = ?", limit, s);
        }
        Random rnd = new Random(23L);
        List<Long> apps = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            apps.add(application(squads.get(rnd.nextInt(squads.size())), volunteer()));
        }
        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        Map<Long, Long> leaderWins = new ConcurrentHashMap<>();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            int roll = rnd.nextInt(12);
            Long app = apps.get(rnd.nextInt(apps.size()));
            Long squad = squads.get(rnd.nextInt(squads.size()));
            Long someone = people.get(rnd.nextInt(people.size()));
            boolean actAsCurrentLeader = rnd.nextInt(3) > 0;
            boolean clearLeader = rnd.nextInt(5) == 0;
            tasks.add(() -> {
                String kind = roll < 3 ? "负责人批准" : roll < 6 ? "负责人拒绝" : roll < 7 ? "部门批准" : roll < 8 ? "部门拒绝"
                        : roll < 10 ? "换负责人" : roll < 11 ? "停用/启用" : "负责人看申请";
                try {
                    Long appSquad = applicationMapper.selectById(app).getSquadId();
                    Long current = squadMapper.selectById(appSquad).getLeaderId();
                    Long actor = actAsCurrentLeader && current != null ? current : someone;
                    switch (kind) {
                        case "负责人批准" -> {
                            squadService.approveByLeader(actor, app);
                            leaderWins.put(app, actor);
                        }
                        case "负责人拒绝" -> {
                            squadService.rejectByLeader(actor, app, "压测拒绝");
                            leaderWins.put(app, actor);
                        }
                        case "部门批准" -> squadService.approveApplication(app, ADMIN);
                        case "部门拒绝" -> squadService.rejectApplication(app, "部门驳回", ADMIN);
                        case "换负责人" -> {
                            SquadDTO change = new SquadDTO();
                            change.setLeaderId(clearLeader ? null : someone);
                            squadService.update(squad, change);
                        }
                        case "停用/启用" -> {
                            VolunteerSquad s = squadMapper.selectById(squad);
                            SquadDTO toggle = new SquadDTO();
                            toggle.setLeaderId(s.getLeaderId());
                            toggle.setStatus(s.getStatus() == 1 ? 0 : 1);
                            squadService.update(squad, toggle);
                        }
                        default -> squadService.applicationsForLeader(actor, appSquad, null, page());
                    }
                    outcomes.computeIfAbsent(kind + "·成功", k -> new AtomicInteger()).incrementAndGet();
                } catch (BusinessException e) {
                    outcomes.computeIfAbsent(kind + "·拒绝·" + e.getMessage(), k -> new AtomicInteger()).incrementAndGet();
                } catch (Exception e) {
                    failures.add(kind + " → " + e);
                }
            });
        }
        long t0 = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable r : tasks) {
            futures.add(pool.submit(r));
        }
        for (Future<?> f : futures) {
            f.get(300, TimeUnit.SECONDS);
        }
        pool.shutdown();
        System.out.println("==== 分队审核压测：" + tasks.size() + " 次 / 16 线程 / " + (System.currentTimeMillis() - t0)
                + " ms ====\n  " + new TreeMap<>(outcomes));
        assertTrue(failures.isEmpty(), "除业务拒绝外不应有任何异常（死锁、锁超时都算）：" + failures);
        for (Long s : squads) {
            assertTrue(volunteerMapper.selectCount(Wrappers.<Volunteer>lambdaQuery().eq(Volunteer::getSquadId, s)) <= limit,
                    "分队 " + s + " 超员");
        }
        for (Long id : apps) {
            SquadApplication row = applicationMapper.selectById(id);
            Long joined = volunteerMapper.selectById(row.getVolunteerId()).getSquadId();
            if (row.getStatus() == 1) {
                assertEquals(row.getSquadId(), joined, "已通过的申请人归属到申请的分队");
            } else {
                assertNull(joined, "没通过的申请人不归属任何分队");
            }
            if (row.getStatus() == 0) {
                assertNull(row.getAuditBy(), "待审的没有审核人");
            }
            Long winner = leaderWins.get(id);
            if (winner != null) {
                assertEquals(SquadService.AUDITOR_SQUAD_LEADER, row.getAuditByType(), "报成功的负责人审核要落成负责人审核");
                assertEquals(winner, row.getAuditBy(), "落库的审核人就是报成功的那位负责人");
            } else if (row.getAuditByType() != null) {
                assertEquals(SquadService.AUDITOR_ADMIN, row.getAuditByType(), "没报成功的负责人不能出现在审核人里");
            }
        }
        assertTrue(outcomes.containsKey("负责人拒绝·成功"), "负责人拒绝成功过：" + outcomes.keySet());
        assertTrue(outcomes.containsKey("负责人拒绝·拒绝·只有分队负责人可以审核本分队的加入申请"),
                "负责人拒绝因已不是负责人 / 分队停用被挡过：" + outcomes.keySet());
    }

    /**
     * 志愿者端分队列表带出「我」与每个分队的关系：待审 → applying，批准 → belonged（2026-10-23 端到端点出来：
     * 此前列表不下发，小程序「归属组织」批准之后仍显示「未归属·申请」）。
     */
    @Test
    void volunteerListMarksPendingAndBelongedForTheViewer() {
        Long squad = squad(volunteer(), 1);
        Long other = squad(volunteer(), 1);
        Long me = volunteer();
        Long app = application(squad, me);

        SquadVO pending = mine(squad, me);
        assertTrue(pending.getApplying() && !pending.getBelonged(), "待审：applying");
        SquadVO untouched = mine(other, me);
        assertTrue(!untouched.getApplying() && !untouched.getBelonged(), "没申请的分队两项都是 false");

        squadService.approveApplication(app, ADMIN);
        SquadVO joined = mine(squad, me);
        assertTrue(joined.getBelonged() && !joined.getApplying(), "批准后：belonged");
        assertTrue(!mine(squad, volunteer()).getBelonged(), "别人看同一分队不是 belonged");
    }

    private SquadVO mine(Long squadId, Long viewer) {
        return squadService.listForVolunteer(page(), viewer).getRecords().stream()
                .filter(v -> v.getId().equals(squadId)).findFirst().orElseThrow();
    }

    /** 负责人与部门同时审同一条：CAS 只放行一个，归属只写一次。 */
    @Test
    void leaderAndDepartmentReviewingTheSameApplication_onlyOneWins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                Long leader = volunteer();
                Long squad = squad(leader, 1);
                Long applicant = volunteer();
                Long appId = application(squad, applicant);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Boolean>> results = new ArrayList<>();
                results.add(pool.submit(() -> attempt(go, () -> squadService.approveByLeader(leader, appId))));
                results.add(pool.submit(() -> attempt(go, () -> squadService.rejectApplication(appId, "部门驳回", ADMIN))));
                go.countDown();
                int wins = 0;
                for (Future<Boolean> f : results) {
                    wins += f.get() ? 1 : 0;
                }
                assertEquals(1, wins, "第 " + round + " 轮：同一条申请只能审一次");
                SquadApplication row = applicationMapper.selectById(appId);
                if (row.getStatus() == 1) {
                    assertEquals(SquadService.AUDITOR_SQUAD_LEADER, row.getAuditByType());
                    assertEquals(squad, volunteerMapper.selectById(applicant).getSquadId());
                } else {
                    assertEquals(SquadService.AUDITOR_ADMIN, row.getAuditByType());
                    assertNull(volunteerMapper.selectById(applicant).getSquadId(), "被驳回的不能归属");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean attempt(CountDownLatch go, Runnable action) throws InterruptedException {
        go.await();
        try {
            action.run();
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    private Long squad(Long leaderId, int status) {
        VolunteerSquad s = new VolunteerSquad();
        s.setName("负责人审核分队_" + SEQ.incrementAndGet());
        s.setType("学校分队");
        s.setLeaderId(leaderId);
        s.setStatus(status);
        s.setMemberLimit(0);
        squadMapper.insert(s);
        return s.getId();
    }

    private Long application(Long squadId, Long volunteerId) {
        SquadApplication a = new SquadApplication();
        a.setSquadId(squadId);
        a.setVolunteerId(volunteerId);
        a.setReason("希望加入");
        a.setStatus(0);
        a.setApplyTime(LocalDateTime.now());
        applicationMapper.insert(a);
        return a.getId();
    }

    private Long volunteer() {
        String phone = "139" + String.format("%08d", SEQ.incrementAndGet() % 100_000_000L);
        Volunteer v = new Volunteer();
        v.setOpenid("sq_leader_" + System.nanoTime() + "_" + SEQ.get());
        v.setRealName("志愿者" + SEQ.get());
        v.setPhone(cryptoUtil.encrypt(phone));
        v.setPhoneHash(cryptoUtil.hashPhone(phone));
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private static PageQuery page() {
        PageQuery q = new PageQuery();
        q.setPage(1);
        q.setSize(100);
        return q;
    }

    private static void assertMessage(String expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(BusinessException.class, action).getMessage());
    }
}
