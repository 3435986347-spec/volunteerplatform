package com.hengde.organization.biz;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.service.VolunteerDeactivationService;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.constant.GroupStatus;
import com.hengde.organization.biz.constant.MemberRole;
import com.hengde.organization.biz.constant.MemberStatus;
import com.hengde.organization.biz.dao.OrganizationStructureMemberMapper;
import com.hengde.organization.biz.dao.OrganizationStructureNodeMapper;
import com.hengde.organization.biz.dao.VolunteerGroupMapper;
import com.hengde.organization.biz.dao.VolunteerGroupMemberMapper;
import com.hengde.organization.biz.dto.StructureDTOs;
import com.hengde.organization.biz.entity.OrganizationStructureMember;
import com.hengde.organization.biz.entity.OrganizationStructureNode;
import com.hengde.organization.biz.entity.VolunteerGroup;
import com.hengde.organization.biz.entity.VolunteerGroupMember;
import com.hengde.organization.biz.service.DeactivationCleanupService;
import com.hengde.organization.biz.service.StructureService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注销落定之后让出组织里的位置（协会 2026-09-16：注销＝退出协会）。
 *
 * <p>要钉住的：落定那一刻（事件）就从组织架构里移出、小组成员身份置为已退出、分队名额让出；
 * <b>组长不自动让位</b>（小组要有人接手，由管理员转移）；事件丢了的由定时兜底补上；没注销的人不受影响。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class DeactivationCleanupTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 10_000_000L);

    @Autowired
    private VolunteerDeactivationService deactivationService;
    @Autowired
    private DeactivationCleanupService cleanupService;
    @Autowired
    private StructureService structureService;
    @Autowired
    private OrganizationStructureNodeMapper nodeMapper;
    @Autowired
    private OrganizationStructureMemberMapper structureMemberMapper;
    @Autowired
    private VolunteerGroupMapper groupMapper;
    @Autowired
    private VolunteerGroupMemberMapper groupMemberMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;

    /** 走真实入口：落定 → 事件 → 让位。落定发生在没有事务的地方（请求闸门那种），监听器要照样被调用。 */
    @Test
    void finalizingADeactivation_givesUpStructureGroupAndSquadPlaces() {
        Long me = volunteer();
        Long leader = volunteer();
        Long bystander = volunteer();
        Long dept = dept();
        structureService.addMember(dept, member(me, "干事"));
        structureService.addMember(dept, member(bystander, "干事"));
        Long group = group(leader);
        groupMember(group, leader, MemberRole.LEADER);
        Long myMembership = groupMember(group, me, MemberRole.MEMBER);
        jdbc.update("UPDATE volunteer SET squad_id = 777 WHERE id = ?", me);

        makeDue(me);
        assertTrue(deactivationService.finalizeIfDue(me));

        assertEquals(0, structureRows(me), "退出协会的人不再挂在组织架构里");
        assertEquals(1, structureRows(bystander), "别人不受影响");
        assertEquals(MemberStatus.LEFT, groupMemberMapper.selectById(myMembership).getStatus(), "小组里置为已退出");
        assertNull(volunteerMapper.selectById(me).getSquadId(), "分队名额让出来");

        // 组长注销：小组要有人接手，自动转给谁是管理决定——组长身份留着，由管理员转移
        makeDue(leader);
        assertTrue(deactivationService.finalizeIfDue(leader));
        VolunteerGroupMember leaderRow = groupMemberMapper.selectOne(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getGroupId, group)
                .eq(VolunteerGroupMember::getVolunteerId, leader));
        assertEquals(MemberStatus.ACTIVE, leaderRow.getStatus());
    }

    /** 事件是进程内、不持久的：监听器没跑到（进程重启、失败）的，由定时兜底按「已注销还占着位置」补上。 */
    @Test
    void aLostEvent_isCaughtUpByTheSweep_andNobodyElseIsTouched() {
        Long gone = volunteer();
        Long stays = volunteer();
        Long dept = dept();
        structureService.addMember(dept, member(gone, "干事"));
        structureService.addMember(dept, member(stays, "干事"));
        Long group = group(volunteer());
        Long goneMembership = groupMember(group, gone, MemberRole.MEMBER);
        Long staysMembership = groupMember(group, stays, MemberRole.MEMBER);
        // 模拟：直接落成已注销、没有发事件
        jdbc.update("UPDATE volunteer SET status = 2, squad_id = 778 WHERE id = ?", gone);

        assertTrue(cleanupService.cleanupAll() >= 1);

        assertEquals(0, structureRows(gone));
        assertEquals(MemberStatus.LEFT, groupMemberMapper.selectById(goneMembership).getStatus());
        assertNull(volunteerMapper.selectById(gone).getSquadId());
        assertEquals(1, structureRows(stays), "没注销的人不动");
        assertEquals(MemberStatus.ACTIVE, groupMemberMapper.selectById(staysMembership).getStatus());
        assertFalse(cleanupService.cleanup(stays), "对没注销的人什么也不做");
        assertFalse(cleanupService.cleanup(gone), "幂等：收拾过的再来一次什么也不动");
    }

    // ---------------- helpers ----------------

    private void makeDue(Long id) {
        jdbc.update("UPDATE volunteer SET deactivate_request_time = DATE_SUB(NOW(), INTERVAL 8 DAY), "
                + "deactivate_effective_time = DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE id = ?", id);
    }

    private long structureRows(Long volunteerId) {
        return structureMemberMapper.selectCount(Wrappers.<OrganizationStructureMember>lambdaQuery()
                .eq(OrganizationStructureMember::getVolunteerId, volunteerId));
    }

    private Long dept() {
        Long root = nodeMapper.selectOne(Wrappers.<OrganizationStructureNode>lambdaQuery()
                .isNull(OrganizationStructureNode::getParentId)).getId();
        return structureService.createNode(StructureServiceTest.node(root, "注销收尾部-" + SEQ.incrementAndGet(), null, 0));
    }

    private static StructureDTOs.MemberSave member(Long volunteerId, String position) {
        StructureDTOs.MemberSave m = new StructureDTOs.MemberSave();
        m.setVolunteerId(volunteerId);
        m.setPosition(position);
        return m;
    }

    private Long group(Long leaderId) {
        VolunteerGroup g = new VolunteerGroup();
        g.setGroupNo("G_DC_" + System.nanoTime());
        g.setName("注销收尾小组_" + SEQ.incrementAndGet());
        g.setLeaderId(leaderId);
        g.setStatus(GroupStatus.ACTIVE);
        groupMapper.insert(g);
        return g.getId();
    }

    private Long groupMember(Long groupId, Long volunteerId, int role) {
        VolunteerGroupMember m = new VolunteerGroupMember();
        m.setGroupId(groupId);
        m.setVolunteerId(volunteerId);
        m.setRole(role);
        m.setStatus(MemberStatus.ACTIVE);
        m.setApplyTime(LocalDateTime.now());
        groupMemberMapper.insert(m);
        return m.getId();
    }

    private Long volunteer() {
        long n = SEQ.incrementAndGet();
        String phone = "137" + String.format("%08d", n % 100_000_000L);
        Volunteer v = new Volunteer();
        v.setOpenid("dc_" + System.nanoTime() + "_" + n);
        v.setRealName("注销收尾" + n);
        v.setPhone(cryptoUtil.encrypt(phone));
        v.setPhoneHash(cryptoUtil.hashPhone(phone));
        v.setStatus(UserStatus.NORMAL);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }
}
