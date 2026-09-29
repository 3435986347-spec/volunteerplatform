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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
