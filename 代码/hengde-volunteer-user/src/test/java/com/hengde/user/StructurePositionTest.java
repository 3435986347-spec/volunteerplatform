package com.hengde.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.page.PageResult;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.OrganizationStructureNodeMapper;
import com.hengde.organization.biz.dto.StructureDTOs;
import com.hengde.organization.biz.entity.OrganizationStructureNode;
import com.hengde.organization.biz.service.StructureService;
import com.hengde.user.dto.VolunteerQueryDTO;
import com.hengde.user.service.AdminVolunteerService;
import com.hengde.user.service.MyProfileService;
import com.hengde.user.vo.AdminVolunteerListVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 名字下面那一行「部门 · 职位」（V4 组织架构维护批，Row 5 F）：由架构位置现算，「我的资料」、志愿者管理列表与详情三处同一个值，
 * 挪部门 / 改职位 / 移出架构即时跟着变；<b>{@code volunteer.position} 那一列不再读</b>。
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class StructurePositionTest {

    @Autowired
    private StructureService structureService;
    @Autowired
    private OrganizationStructureNodeMapper nodeMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private MyProfileService myProfileService;
    @Autowired
    private AdminVolunteerService adminVolunteerService;

    @Test
    void profileAndAdminViews_showDeptAndPosition_andFollowEveryChange() {
        String tag = Long.toString(System.nanoTime(), 36);
        Long root = nodeMapper.selectOne(Wrappers.<OrganizationStructureNode>lambdaQuery()
                .isNull(OrganizationStructureNode::getParentId)).getId();
        Long deptA = structureService.createNode(node(root, "职位部A-" + tag));
        Long deptB = structureService.createNode(node(root, "职位部B-" + tag));

        Volunteer v = new Volunteer();
        v.setOpenid("test:position:" + tag);
        v.setRealName("职位测试");
        v.setSchool("职位学校-" + tag);
        v.setStatus(0);
        v.setManagerFlag(0);
        v.setRegisterTime(LocalDateTime.now());
        v.setPosition("V1 老列里的旧值");
        volunteerMapper.insert(v);
        Long vid = v.getId();

        assertNull(myProfileService.getMyProfile(vid).getPosition(), "不在架构里就没有这一行，老列不再读");
        assertNull(listRow(vid, "职位学校-" + tag).getPosition());

        StructureDTOs.MemberSave save = new StructureDTOs.MemberSave();
        save.setVolunteerId(vid);
        save.setPosition("部长");
        Long memberId = structureService.addMember(deptA, save);
        assertAllThree(vid, tag, "职位部A-" + tag + " · 部长");

        StructureDTOs.MemberUpdate up = new StructureDTOs.MemberUpdate();
        up.setNodeId(deptB);
        up.setPosition("副部长");
        structureService.updateMember(memberId, up);
        assertAllThree(vid, tag, "职位部B-" + tag + " · 副部长");

        structureService.updateNode(deptB, node(root, "改名部-" + tag));
        assertAllThree(vid, tag, "改名部-" + tag + " · 副部长");

        structureService.removeMember(memberId);
        assertAllThree(vid, tag, null);
    }

    private void assertAllThree(Long vid, String tag, String expected) {
        assertEquals(expected, myProfileService.getMyProfile(vid).getPosition(), "我的资料");
        assertEquals(expected, listRow(vid, "职位学校-" + tag).getPosition(), "志愿者管理列表");
        assertEquals(expected, adminVolunteerService.detail(vid).getPosition(), "志愿者详情");
    }

    private AdminVolunteerListVO listRow(Long vid, String school) {
        VolunteerQueryDTO q = new VolunteerQueryDTO();
        q.setKeyword(school);
        PageResult<AdminVolunteerListVO> page = adminVolunteerService.list(q);
        return page.getRecords().stream().filter(r -> r.getId().equals(vid)).findFirst().orElseThrow();
    }

    private static StructureDTOs.NodeSave node(Long parentId, String name) {
        StructureDTOs.NodeSave d = new StructureDTOs.NodeSave();
        d.setParentId(parentId);
        d.setName(name);
        return d;
    }
}
