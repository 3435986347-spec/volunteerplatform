package com.hengde.organization.biz;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.VolunteerGroupMapper;
import com.hengde.organization.biz.dao.VolunteerGroupMemberMapper;
import com.hengde.organization.biz.dao.VolunteerSquadMapper;
import com.hengde.organization.biz.entity.VolunteerGroup;
import com.hengde.organization.biz.entity.VolunteerGroupMember;
import com.hengde.organization.biz.entity.VolunteerSquad;
import com.hengde.organization.biz.service.GroupService;
import com.hengde.organization.biz.service.SquadService;
import com.hengde.organization.biz.vo.GroupVO;
import com.hengde.organization.biz.vo.SquadVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 列表批量组装（B1/B3）正确性：组/分队列表 VO 的 memberCount 由一次 group-by 聚合得到，
 * 组长姓名由一次批量解析得到——验证 selectMaps 聚合的计数正确、只算 ACTIVE 成员、组长名能回填。
 * <b>需本机有 Docker。</b>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class ListCountBatchTest {

    private static final int GROUP_ACTIVE = 1;
    private static final int MEMBER_ACTIVE = 1;
    private static final int MEMBER_LEFT = 3;
    private static final int ROLE_LEADER = 1;
    private static final int ROLE_MEMBER = 0;
    private static final int SQUAD_ENABLED = 1;

    @Autowired
    private GroupService groupService;
    @Autowired
    private SquadService squadService;
    @Autowired
    private VolunteerGroupMapper groupMapper;
    @Autowired
    private VolunteerGroupMemberMapper memberMapper;
    @Autowired
    private VolunteerSquadMapper squadMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;

    @Test
    void groupList_countsOnlyActiveMembers_andResolvesLeaderName() {
        Long leaderId = insertVolunteer("组长甲", null);
        Long groupId = insertActiveGroup("计数小组", leaderId);
        insertMember(groupId, leaderId, ROLE_LEADER, MEMBER_ACTIVE);
        insertMember(groupId, insertVolunteer("成员乙", null), ROLE_MEMBER, MEMBER_ACTIVE);
        insertMember(groupId, insertVolunteer("成员丙", null), ROLE_MEMBER, MEMBER_ACTIVE);
        // 已退出成员不应计入
        insertMember(groupId, insertVolunteer("退出丁", null), ROLE_MEMBER, MEMBER_LEFT);

        GroupVO vo = findGroup(groupId);
        assertEquals(3L, vo.getMemberCount(), "memberCount 仅算 ACTIVE 成员");
        assertEquals("组长甲", vo.getLeaderName(), "组长姓名应批量回填");
    }

    @Test
    void squadList_countsMembersBySquadId() {
        Long squadId = insertSquad("计数分队");
        insertVolunteer("分队成员1", squadId);
        insertVolunteer("分队成员2", squadId);
        // 归属别的分队的志愿者不应计入本分队
        Long otherSquad = insertSquad("别的分队");
        insertVolunteer("别队成员", otherSquad);

        SquadVO vo = findSquad(squadId);
        assertEquals(2L, vo.getMemberCount(), "memberCount 按 squad_id 聚合，仅算本分队");
    }

    // ---------- helpers ----------

    private GroupVO findGroup(Long groupId) {
        return groupService.list(new PageQuery(), null, true).getRecords().stream()
                .filter(g -> groupId.equals(g.getId())).findFirst().orElseThrow();
    }

    private SquadVO findSquad(Long squadId) {
        return squadService.list(new PageQuery(), true).getRecords().stream()
                .filter(s -> squadId.equals(s.getId())).findFirst().orElseThrow();
    }

    private Long insertVolunteer(String realName, Long squadId) {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime());
        v.setRealName(realName);
        v.setStatus(0);
        v.setSquadId(squadId);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private Long insertActiveGroup(String name, Long leaderId) {
        VolunteerGroup g = new VolunteerGroup();
        g.setGroupNo("G" + System.nanoTime());
        g.setName(name);
        g.setLeaderId(leaderId);
        g.setStatus(GROUP_ACTIVE);
        groupMapper.insert(g);
        return g.getId();
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

    private Long insertSquad(String name) {
        VolunteerSquad squad = new VolunteerSquad();
        squad.setName(name);
        squad.setType("学校");
        squad.setStatus(SQUAD_ENABLED);
        squad.setMemberLimit(0);
        squadMapper.insert(squad);
        return squad.getId();
    }
}
