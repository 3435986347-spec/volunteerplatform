package com.hengde.organization.biz;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.OrganizationStructureNodeMapper;
import com.hengde.organization.biz.dao.VolunteerGroupLeaderHistoryMapper;
import com.hengde.organization.biz.dao.VolunteerGroupMapper;
import com.hengde.organization.biz.dao.VolunteerGroupMemberMapper;
import com.hengde.organization.biz.dao.VolunteerSquadMapper;
import com.hengde.organization.biz.dto.GroupCreateDTO;
import com.hengde.organization.biz.dto.SquadDTO;
import com.hengde.organization.biz.entity.VolunteerGroup;
import com.hengde.organization.biz.entity.VolunteerGroupLeaderHistory;
import com.hengde.organization.biz.entity.VolunteerGroupMember;
import com.hengde.organization.biz.service.GroupService;
import com.hengde.organization.biz.service.SquadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class OrganizationBizTest {

    private OrganizationStructureNodeMapper nodeMapper;
    private VolunteerSquadMapper squadMapper;
    private SquadService squadService;
    private GroupService groupService;
    private VolunteerGroupMapper groupMapper;
    private VolunteerGroupMemberMapper memberMapper;
    private VolunteerGroupLeaderHistoryMapper leaderHistoryMapper;
    private VolunteerMapper volunteerMapper;

    @Autowired
    public void setNodeMapper(OrganizationStructureNodeMapper nodeMapper) {
        this.nodeMapper = nodeMapper;
    }

    @Autowired
    public void setSquadMapper(VolunteerSquadMapper squadMapper) {
        this.squadMapper = squadMapper;
    }

    @Autowired
    public void setSquadService(SquadService squadService) {
        this.squadService = squadService;
    }

    @Autowired
    public void setGroupService(GroupService groupService) {
        this.groupService = groupService;
    }

    @Autowired
    public void setGroupMapper(VolunteerGroupMapper groupMapper) {
        this.groupMapper = groupMapper;
    }

    @Autowired
    public void setMemberMapper(VolunteerGroupMemberMapper memberMapper) {
        this.memberMapper = memberMapper;
    }

    @Autowired
    public void setLeaderHistoryMapper(VolunteerGroupLeaderHistoryMapper leaderHistoryMapper) {
        this.leaderHistoryMapper = leaderHistoryMapper;
    }

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    @Test
    void organizationMigrationCreatesSeededStructure() {
        assertTrue(nodeMapper.selectCount(null) >= 4);
    }

    @Test
    void canCreateSquad() {
        SquadDTO dto = new SquadDTO();
        dto.setName("测试分队");
        dto.setType("学校");
        dto.setLeaderName("负责人");
        dto.setLeaderPhone("13800000000");

        Long id = squadService.create(dto);

        assertNotNull(id);
        assertNotNull(squadMapper.selectById(id));
    }

    // ---------- V7 fix 2: 建组期堵住「一人一组」漏洞 ----------

    @Test
    void approveCreate_existingPendingLeaderMember_upgradesNotInsertsNewRow() {
        // 模拟「create() 已插好 PENDING 组长成员行」的状态，验证 approveCreate 是 update 而非 insert——
        // 否则会产生两条 LEADER member 行，污染「一人一组」统计与 leader_history 起点
        Long leaderId = 9001L;
        Long adminId = 100L;
        VolunteerGroup g = insertGroup(leaderId, 0); // status=PENDING
        insertMember(g.getId(), leaderId, 1, 0);     // role=LEADER, status=PENDING（就像 create() 已经插过）

        groupService.approveCreate(g.getId(), adminId);

        // 仅一条该志愿者的 member 行，且已升级为 ACTIVE
        List<VolunteerGroupMember> rows = memberMapper.selectList(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getGroupId, g.getId())
                .eq(VolunteerGroupMember::getVolunteerId, leaderId));
        assertEquals(1, rows.size(), "approveCreate 应升级已有 PENDING 行而非插新行");
        assertEquals(1, rows.get(0).getStatus(), "升级后状态应为 ACTIVE");
        assertEquals(adminId, rows.get(0).getAuditBy());
        // 历史写入了一条「首次任命」
        Long histCount = leaderHistoryMapper.selectCount(Wrappers.<VolunteerGroupLeaderHistory>lambdaQuery()
                .eq(VolunteerGroupLeaderHistory::getGroupId, g.getId()));
        assertEquals(1L, histCount);
    }

    @Test
    void rejectCreate_releasesPendingLeaderMember() {
        // rejectCreate 应该把 create() 时插的 PENDING 组长成员置为 REJECTED，
        // 否则申请人会被「一人一组」永远卡住，无法再发起新组
        Long leaderId = 9002L;
        VolunteerGroup g = insertGroup(leaderId, 0);
        insertMember(g.getId(), leaderId, 1, 0);

        groupService.rejectCreate(g.getId(), "信息不全");

        VolunteerGroupMember row = memberMapper.selectOne(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getGroupId, g.getId())
                .eq(VolunteerGroupMember::getVolunteerId, leaderId)
                .last("limit 1"));
        assertEquals(2, row.getStatus(), "PENDING 应被置 REJECTED 释放占用");
    }

    // ---------- V7 fix 5: dissolve 状态守卫 ----------

    @Test
    void dissolve_pendingGroup_rejected() {
        VolunteerGroup g = insertGroup(7001L, 0); // PENDING
        BusinessException ex = assertThrows(BusinessException.class,
                () -> groupService.dissolve(g.getId(), "试图直接解散", 1L));
        assertTrue(ex.getMessage().contains("正常状态"));
    }

    @Test
    void dissolve_alreadyDissolved_rejected() {
        VolunteerGroup g = insertGroup(7002L, 3); // DISSOLVED
        BusinessException ex = assertThrows(BusinessException.class,
                () -> groupService.dissolve(g.getId(), "再来一次", 1L));
        assertTrue(ex.getMessage().contains("正常状态"));
    }

    @Test
    void dissolve_activeGroup_writesDissolveFields() {
        Long leaderId = 7003L;
        VolunteerGroup g = insertGroup(leaderId, 1); // ACTIVE
        insertMember(g.getId(), leaderId, 1, 1);

        groupService.dissolve(g.getId(), "组员流失", 99L);

        VolunteerGroup after = groupMapper.selectById(g.getId());
        assertEquals(3, after.getStatus());
        assertNotNull(after.getDissolveTime());
        assertEquals("组员流失", after.getDissolveReason());
        assertEquals(99L, after.getDissolveBy());
        // 成员清空：ACTIVE → REMOVED
        VolunteerGroupMember m = memberMapper.selectOne(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getGroupId, g.getId())
                .eq(VolunteerGroupMember::getVolunteerId, leaderId)
                .last("limit 1"));
        assertEquals(4, m.getStatus());
    }

    // ---------- 并发：志愿者维度锁防同人多组 ----------

    @Test
    void create_concurrentSameVolunteer_onlyOneSucceeds() throws Exception {
        // 关键不变量：同一志愿者并发触发 N 次建组，应当只有 1 个成功落库，其余被「一人一组」拦截。
        // 没有 Redisson 锁时两个并发请求会都读到空、各自插一条，最终一人多组。
        Long vid = insertNormalVolunteer();
        int N = 5;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger biz = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(N);
        Future<?>[] futures = new Future[N];
        for (int i = 0; i < N; i++) {
            futures[i] = pool.submit(() -> {
                try {
                    start.await();
                    GroupCreateDTO dto = new GroupCreateDTO();
                    dto.setName("并发测试_" + Thread.currentThread().getId());
                    // 直接走带 volunteerId 的内部入口绕过 Sa-Token ThreadLocal——
                    // 与生产路径(create)只差「id 来源」，锁与事务路径完全相同
                    groupService.createForVolunteer(vid, dto);
                    ok.incrementAndGet();
                } catch (BusinessException ex) {
                    biz.incrementAndGet();
                } catch (Throwable t) {
                    other.incrementAndGet();
                    t.printStackTrace();
                }
            });
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertEquals(0, other.get(), "不应有非业务异常");
        assertEquals(1, ok.get(), "锁应使只有 1 个建组成功");
        assertEquals(N - 1, biz.get(), "其他应被「一人一组」拦截");

        // 数据库视角：该志愿者只有 1 条 PENDING 组长成员行
        Long pendingCount = memberMapper.selectCount(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getVolunteerId, vid)
                .eq(VolunteerGroupMember::getStatus, 0));
        assertEquals(1L, pendingCount);
    }

    // ---------- helpers ----------

    /** 插入一个 status=正常 的志愿者，返回 id。 */
    private Long insertNormalVolunteer() {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime());
        v.setRealName("测试");
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private VolunteerGroup insertGroup(Long leaderId, int status) {
        VolunteerGroup g = new VolunteerGroup();
        g.setGroupNo("G_" + System.nanoTime());
        g.setName("测试小组_" + System.nanoTime());
        g.setLeaderId(leaderId);
        g.setStatus(status);
        groupMapper.insert(g);
        return g;
    }

    private void insertMember(Long groupId, Long volunteerId, int role, int status) {
        VolunteerGroupMember m = new VolunteerGroupMember();
        m.setGroupId(groupId);
        m.setVolunteerId(volunteerId);
        m.setRole(role);
        m.setStatus(status);
        m.setApplyTime(LocalDateTime.now());
        memberMapper.insert(m);
    }

    // ---------- V9 CAS 审批：重复/并发调用 ----------

    @Test
    void approveCreate_secondCall_rejected() {
        // 第一次 approve 成功后 group.status = ACTIVE，第二次 CAS 拿不到 PENDING 行 → 业务异常
        Long leaderId = insertNormalVolunteer();
        VolunteerGroup g = insertGroup(leaderId, 0);      // PENDING
        insertMember(g.getId(), leaderId, 1, 0);           // PENDING 组长成员

        groupService.approveCreate(g.getId(), 1L);         // 第一次：应成功
        BusinessException ex = assertThrows(BusinessException.class,
                () -> groupService.approveCreate(g.getId(), 1L));  // 第二次：CAS 命中 0 行
        assertTrue(ex.getMessage().contains("待审核"));
    }

    @Test
    void rejectCreate_secondCall_rejected() {
        Long leaderId = insertNormalVolunteer();
        VolunteerGroup g = insertGroup(leaderId, 0);
        insertMember(g.getId(), leaderId, 1, 0);

        groupService.rejectCreate(g.getId(), "不符合要求");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> groupService.rejectCreate(g.getId(), "再来一次"));
        assertTrue(ex.getMessage().contains("待审核"));
    }

    @Test
    void approveMember_secondCall_rejected() {
        // 第一次 approve 成功后 status = ACTIVE，第二次 CAS where status=PENDING 命中 0 → 业务异常
        Long leaderId = insertNormalVolunteer();
        Long applicantId = insertNormalVolunteer();
        VolunteerGroup g = insertGroup(leaderId, 1);        // ACTIVE 小组
        insertMember(g.getId(), leaderId, 1, 1);             // ACTIVE 组长
        insertMember(g.getId(), applicantId, 0, 0);          // PENDING 申请者

        VolunteerGroupMember app = memberMapper.selectOne(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getGroupId, g.getId())
                .eq(VolunteerGroupMember::getVolunteerId, applicantId)
                .last("limit 1"));

        // 用 approveMemberBy 绕过 Sa-Token 上下文，auditorId = leaderId
        groupService.approveMemberBy(g.getId(), app.getId(), leaderId);   // 第一次：成功

        BusinessException ex = assertThrows(BusinessException.class,
                () -> groupService.approveMemberBy(g.getId(), app.getId(), leaderId));  // 第二次：CAS 0 行
        assertTrue(ex.getMessage().contains("待审核"));
    }

    @Test
    void rejectMember_secondCall_rejected() {
        Long leaderId = insertNormalVolunteer();
        Long applicantId = insertNormalVolunteer();
        VolunteerGroup g = insertGroup(leaderId, 1);
        insertMember(g.getId(), leaderId, 1, 1);
        insertMember(g.getId(), applicantId, 0, 0);

        VolunteerGroupMember app = memberMapper.selectOne(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getGroupId, g.getId())
                .eq(VolunteerGroupMember::getVolunteerId, applicantId)
                .last("limit 1"));

        groupService.rejectMemberBy(g.getId(), app.getId(), leaderId);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> groupService.rejectMemberBy(g.getId(), app.getId(), leaderId));
        assertTrue(ex.getMessage().contains("待审核"));
    }
}
