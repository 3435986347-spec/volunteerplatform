package com.hengde.organization.biz;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.testsupport.RecordingSmsConfig;
import com.hengde.common.testsupport.RecordingSmsConfig.RecordingSmsService;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.SquadApplicationMapper;
import com.hengde.organization.biz.dao.VolunteerGroupMapper;
import com.hengde.organization.biz.dao.VolunteerGroupMemberMapper;
import com.hengde.organization.biz.dao.VolunteerSquadMapper;
import com.hengde.organization.biz.dto.ManagerApplyDTO;
import com.hengde.organization.biz.entity.SquadApplication;
import com.hengde.organization.biz.entity.VolunteerGroup;
import com.hengde.organization.biz.entity.VolunteerGroupMember;
import com.hengde.organization.biz.entity.VolunteerSquad;
import com.hengde.organization.biz.service.GroupService;
import com.hengde.organization.biz.service.ManagerApplicationService;
import com.hengde.organization.biz.service.SquadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * organization 域通知短信的接线验证：分队加入、报名管理团队（共用 {@code org-join-result}）
 * 与志愿小组建组/加入（{@code group-join-result}）。
 *
 * <p>这几处的共同点是：申请人提交之后就在等结果，而系统里<b>没有别的地方会主动告诉他</b>——
 * 不发短信他就得自己隔三差五回小程序翻申请页。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = {
        "hengde.sms.templates.org-join-result=T-ORG-JOIN",
        "hengde.sms.templates.group-join-result=T-GROUP-JOIN"
})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, RecordingSmsConfig.class})
class OrganizationSmsNotifyTest {

    private static final long ADMIN = 100L;

    @Autowired
    private RecordingSmsService sms;
    @Autowired
    private SquadService squadService;
    @Autowired
    private GroupService groupService;
    @Autowired
    private ManagerApplicationService managerApplicationService;

    @Autowired
    private VolunteerSquadMapper squadMapper;
    @Autowired
    private SquadApplicationMapper squadApplicationMapper;
    @Autowired
    private VolunteerGroupMapper groupMapper;
    @Autowired
    private VolunteerGroupMemberMapper memberMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;

    @BeforeEach
    void reset() {
        sms.clear();
    }

    @Test
    void squadApplicationApproved_tellsApplicantWhichSquad() {
        Long squadId = insertSquad("青年志愿者服务队");
        Long vid = insertVolunteer("13922220001");
        Long appId = insertSquadApplication(squadId, vid);

        squadService.approveApplication(appId);

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-ORG-JOIN");
        assertEquals(1, sent.size());
        assertEquals("13922220001", sent.get(0).phone());
        assertEquals("青年志愿者服务队", sent.get(0).params().get("orgName"));
        assertEquals("通过", sent.get(0).params().get("status"));
    }

    @Test
    void squadApplicationRejected_carriesReason() {
        Long squadId = insertSquad("协会南兴分队");
        Long vid = insertVolunteer("13922220002");
        Long appId = insertSquadApplication(squadId, vid);

        squadService.rejectApplication(appId, "分队人数已满");

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-ORG-JOIN");
        assertEquals(1, sent.size());
        assertEquals("未通过", sent.get(0).params().get("status"));
        assertEquals("分队人数已满", sent.get(0).params().get("remark"));
    }

    @Test
    void managerApplication_reusesOrgJoinTemplateWithFixedOrgName() {
        Long vid = insertVolunteer("13922220011");
        ManagerApplyDTO dto = new ManagerApplyDTO();
        dto.setReason("想参与活动组织");
        Long id = managerApplicationService.apply(vid, dto);
        sms.clear();

        managerApplicationService.approve(id, ADMIN);

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-ORG-JOIN");
        assertEquals(1, sent.size());
        assertEquals("管理团队", sent.get(0).params().get("orgName"),
                "与分队共用一条模板，orgName 填「管理团队」——不必为它另报备一条");
        assertEquals("通过", sent.get(0).params().get("status"));
    }

    @Test
    void managerApplicationRejected_carriesReason() {
        Long vid = insertVolunteer("13922220012");
        ManagerApplyDTO dto = new ManagerApplyDTO();
        dto.setReason("想参与活动组织");
        Long id = managerApplicationService.apply(vid, dto);
        sms.clear();

        managerApplicationService.reject(id, "服务时长不足", ADMIN);

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-ORG-JOIN");
        assertEquals(1, sent.size());
        assertEquals("未通过", sent.get(0).params().get("status"));
        assertEquals("服务时长不足", sent.get(0).params().get("remark"));
    }

    @Test
    void groupJoinApproved_andRejected_bothNotifyApplicant() {
        Long leader = insertVolunteer("13922220021");
        Long groupId = insertActiveGroup(leader, "南渡河护河小组");
        Long joiner = insertVolunteer("13922220022");
        Long memberId = insertPendingMember(groupId, joiner);

        groupService.approveMemberBy(groupId, memberId, leader);

        List<RecordingSmsService.Sent> approved = sms.byTemplateId("T-GROUP-JOIN");
        assertEquals(1, approved.size());
        assertEquals("13922220022", approved.get(0).phone());
        assertEquals("南渡河护河小组", approved.get(0).params().get("teamName"));
        assertEquals("已通过", approved.get(0).params().get("result"));

        // 另一位申请人被拒
        sms.clear();
        Long refused = insertVolunteer("13922220023");
        Long refusedMemberId = insertPendingMember(groupId, refused);
        groupService.rejectMemberBy(groupId, refusedMemberId, leader);

        List<RecordingSmsService.Sent> rejected = sms.byTemplateId("T-GROUP-JOIN");
        assertEquals(1, rejected.size());
        assertEquals("13922220023", rejected.get(0).phone());
        assertEquals("未通过", rejected.get(0).params().get("result"));
    }

    @Test
    void groupCreateApproved_notifiesFounder() {
        Long founder = insertVolunteer("13922220031");
        Long groupId = insertPendingGroup(founder, "待批小组");

        groupService.approveCreate(groupId, ADMIN);

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-GROUP-JOIN");
        assertEquals(1, sent.size(), "发起人等的就是这个结果");
        assertEquals("13922220031", sent.get(0).phone());
        assertEquals("待批小组", sent.get(0).params().get("teamName"));
        assertEquals("已通过", sent.get(0).params().get("result"));
    }

    @Test
    void groupCreateRejected_notifiesFounderWithReason() {
        Long founder = insertVolunteer("13922220032");
        Long groupId = insertPendingGroup(founder, "被驳回小组");

        groupService.rejectCreate(groupId, "名称与已有小组重复");

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-GROUP-JOIN");
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).params().get("result").contains("名称与已有小组重复"),
                "驳回原因得说出来，否则发起人不知道怎么改；实际=" + sent.get(0).params().get("result"));
    }

    // ---------- 造数 ----------

    private Long insertSquad(String name) {
        VolunteerSquad s = new VolunteerSquad();
        s.setName(name + "_" + System.nanoTime());
        s.setType("学校分队");
        s.setStatus(1);
        s.setMemberLimit(0);
        squadMapper.insert(s);
        // 用例断言的是原始名字，这里把它改回不带后缀的可读名（名称无唯一约束）
        s.setName(name);
        squadMapper.updateById(s);
        return s.getId();
    }

    private Long insertSquadApplication(Long squadId, Long volunteerId) {
        SquadApplication a = new SquadApplication();
        a.setSquadId(squadId);
        a.setVolunteerId(volunteerId);
        a.setReason("希望加入");
        a.setStatus(0);
        a.setApplyTime(LocalDateTime.now());
        squadApplicationMapper.insert(a);
        return a.getId();
    }

    private Long insertActiveGroup(Long leaderId, String name) {
        VolunteerGroup g = new VolunteerGroup();
        g.setGroupNo("G_sms_" + System.nanoTime());
        g.setName(name);
        g.setLeaderId(leaderId);
        g.setStatus(1);
        groupMapper.insert(g);
        VolunteerGroupMember m = new VolunteerGroupMember();
        m.setGroupId(g.getId());
        m.setVolunteerId(leaderId);
        m.setRole(1);
        m.setStatus(1);
        m.setApplyTime(LocalDateTime.now());
        m.setAuditTime(LocalDateTime.now());
        memberMapper.insert(m);
        return g.getId();
    }

    private Long insertPendingGroup(Long founderId, String name) {
        VolunteerGroup g = new VolunteerGroup();
        g.setGroupNo("G_sms_" + System.nanoTime());
        g.setName(name);
        g.setLeaderId(founderId);
        g.setStatus(0);
        groupMapper.insert(g);
        VolunteerGroupMember m = new VolunteerGroupMember();
        m.setGroupId(g.getId());
        m.setVolunteerId(founderId);
        m.setRole(1);
        m.setStatus(0);
        m.setApplyTime(LocalDateTime.now());
        memberMapper.insert(m);
        return g.getId();
    }

    private Long insertPendingMember(Long groupId, Long volunteerId) {
        VolunteerGroupMember m = new VolunteerGroupMember();
        m.setGroupId(groupId);
        m.setVolunteerId(volunteerId);
        m.setRole(0);
        m.setStatus(0);
        m.setApplyTime(LocalDateTime.now());
        memberMapper.insert(m);
        return m.getId();
    }

    private Long insertVolunteer(String phonePlain) {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime());
        v.setRealName("志愿者_" + System.nanoTime());
        v.setPhone(cryptoUtil.encrypt(phonePlain));
        v.setPhoneHash(cryptoUtil.hashPhone(phonePlain));
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }
}
