package com.hengde.organization.biz;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.OrganizationStructureNodeMapper;
import com.hengde.organization.biz.dto.StructureDTOs;
import com.hengde.organization.biz.entity.OrganizationStructureNode;
import com.hengde.organization.biz.service.StructureService;
import com.hengde.organization.biz.vo.StructureNodeVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组织架构维护（V4 组织架构维护批，V69）：节点增删改与环检测、删除守卫、放人 / 挪人 / 移出、「一个人一个位置」、
 * 名字下面那一行「部门 · 职位」、电话只给该看的人。
 *
 * <p>架构是全库共享的一棵树，各用例在根下面自建一个子树互不干扰，断言只看自己建的那几个节点。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class StructureServiceTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);

    @Autowired
    private StructureService structureService;
    @Autowired
    private OrganizationStructureNodeMapper nodeMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void nodes_createUpdateMove_andCycleRules() {
        Long root = rootId();
        Long dept = structureService.createNode(node(root, "测试部-" + SEQ.incrementAndGet(), "说明", 5));
        Long team = structureService.createNode(node(dept, "一组", null, 1));
        Long sub = structureService.createNode(node(team, "一组一班", null, 1));

        assertMessage("上级", () -> structureService.createNode(node(null, "没有上级", null, 0)));
        assertMessage("不存在", () -> structureService.createNode(node(Long.MAX_VALUE, "上级不存在", null, 0)));
        assertMessage("名称", () -> structureService.createNode(node(dept, "  ", null, 0)));

        // 改名 / 说明 / 排序，不挪
        structureService.updateNode(team, node(dept, "第一组", "改过的说明", 9));
        OrganizationStructureNode t = nodeMapper.selectById(team);
        assertEquals("第一组", t.getName());
        assertEquals("改过的说明", t.getTitle());
        assertEquals(9, t.getSort());
        assertEquals(dept, t.getParentId());

        // 说明清空要真的清掉
        structureService.updateNode(team, node(dept, "第一组", null, null));
        assertNull(nodeMapper.selectById(team).getTitle());
        assertEquals(9, nodeMapper.selectById(team).getSort(), "不传排序＝保留");

        // 挪到自己 / 自己的子孙下面：拒绝
        assertMessage("下级", () -> structureService.updateNode(dept, node(dept, "测试部", null, null)));
        assertMessage("下级", () -> structureService.updateNode(dept, node(team, "测试部", null, null)));
        assertMessage("下级", () -> structureService.updateNode(dept, node(sub, "测试部", null, null)));
        assertEquals(root, nodeMapper.selectById(dept).getParentId(), "被拒的挪动不落库");

        // 合法挪动：一班挪到部门下面
        structureService.updateNode(sub, node(dept, "一组一班", null, null));
        assertEquals(dept, nodeMapper.selectById(sub).getParentId());

        // 非根节点不能改成没有上级；根不能挂到别处
        assertMessage("上级", () -> structureService.updateNode(team, node(null, "第一组", null, null)));
        assertMessage("根节点", () -> structureService.updateNode(root, node(dept, "协会", null, null)));
        String rootName = nodeMapper.selectById(root).getName();
        structureService.updateNode(root, node(null, rootName, "协会", null));
        assertNull(nodeMapper.selectById(root).getParentId());
    }

    @Test
    void nodes_depthLimit_countsTheSubtreeBeingMoved() {
        Long root = rootId();
        Long top = structureService.createNode(node(root, "深度-" + SEQ.incrementAndGet(), null, 0));
        Long cur = top;
        // 根深 0，top 深 1，一直建到深 9（第 10 层）
        for (int d = 2; d <= 9; d++) {
            cur = structureService.createNode(node(cur, "L" + d, null, 0));
        }
        Long deepest = cur;
        assertMessage("层级", () -> structureService.createNode(node(deepest, "L10", null, 0)));

        // 一个两层的小子树（深 1、深 2），挪到深 8 的节点下面会变成深 9、深 10：拒绝
        Long small = structureService.createNode(node(root, "小子树-" + SEQ.incrementAndGet(), null, 0));
        structureService.createNode(node(small, "小子树的叶子", null, 0));
        Long depth8 = nodeMapper.selectById(deepest).getParentId();
        assertMessage("层级", () -> structureService.updateNode(small, node(depth8, "小子树", null, null)));
        Long depth7 = nodeMapper.selectById(depth8).getParentId();
        structureService.updateNode(small, node(depth7, "小子树", null, null));
        assertEquals(depth7, nodeMapper.selectById(small).getParentId(), "挪到深 7 下面刚好到第 10 层以内");
    }

    @Test
    void deleteNode_guards() {
        Long root = rootId();
        assertMessage("根节点", () -> structureService.deleteNode(root));

        Long dept = structureService.createNode(node(root, "删除-" + SEQ.incrementAndGet(), null, 0));
        Long child = structureService.createNode(node(dept, "子节点", null, 0));
        assertMessage("下面还有 1 个节点", () -> structureService.deleteNode(dept));

        Long vid = volunteer(true, 0, null);
        Long memberId = structureService.addMember(child, member(vid, "干事"));
        assertMessage("还有 1 个人", () -> structureService.deleteNode(child));

        structureService.removeMember(memberId);
        structureService.deleteNode(child);
        structureService.deleteNode(dept);
        assertNull(nodeMapper.selectById(dept));
        assertMessage("不存在", () -> structureService.deleteNode(dept));
    }

    @Test
    void members_addMoveRemove_onePlacePerPerson_andOnlyActiveRegistered() {
        Long root = rootId();
        String deptName = "放人部-" + SEQ.incrementAndGet();
        Long deptA = structureService.createNode(node(root, deptName, null, 0));
        Long deptB = structureService.createNode(node(root, "挪人部-" + SEQ.incrementAndGet(), null, 0));
        Long vid = volunteer(true, 0, "13811112222");

        Long m = structureService.addMember(deptA, member(vid, " 部长 "));
        assertEquals(Map.of(vid, deptName + " · 部长"), structureService.positionLabelsOf(List.of(vid, Long.MAX_VALUE)),
                "职位去掉首尾空白；不在架构里的人不在返回里");

        assertMessage("已经在架构里", () -> structureService.addMember(deptB, member(vid, "副部长")));
        assertMessage("已经在架构里", () -> structureService.addMember(deptA, member(vid, "部长")));
        assertMessage("已实名", () -> structureService.addMember(deptA, member(volunteer(false, 0, null), "游客")));
        assertMessage("已实名", () -> structureService.addMember(deptA, member(volunteer(true, 1, null), "被禁用的")));
        assertMessage("不存在", () -> structureService.addMember(Long.MAX_VALUE, member(volunteer(true, 0, null), "x")));
        assertMessage("职位", () -> structureService.addMember(deptA, member(volunteer(true, 0, null), "")));

        // 挪到另一个部门、改职位
        StructureDTOs.MemberUpdate up = new StructureDTOs.MemberUpdate();
        up.setNodeId(deptB);
        up.setPosition("副部长");
        up.setSort(3);
        structureService.updateMember(m, up);
        String deptBName = nodeMapper.selectById(deptB).getName();
        assertEquals(deptBName + " · 副部长", structureService.positionLabelsOf(List.of(vid)).get(vid));
        assertMessage("不存在", () -> {
            StructureDTOs.MemberUpdate bad = new StructureDTOs.MemberUpdate();
            bad.setNodeId(Long.MAX_VALUE);
            bad.setPosition("x");
            structureService.updateMember(m, bad);
        });

        // 移出后名字下面那一行消失，且可以重新放进去（软删不占唯一键）
        structureService.removeMember(m);
        assertFalse(structureService.positionLabelsOf(List.of(vid)).containsKey(vid));
        assertMessage("没有这个位置", () -> structureService.removeMember(m));
        Long again = structureService.addMember(deptA, member(vid, "干事"));
        assertNotNull(again);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM organization_structure_member WHERE volunteer_id = ? AND is_deleted = 0",
                Integer.class, vid));
    }

    @Test
    void tree_carriesMembers_andPhoneOnlyWhenAsked() {
        Long root = rootId();
        Long dept = structureService.createNode(node(root, "树-" + SEQ.incrementAndGet(), null, 0));
        Long child = structureService.createNode(node(dept, "树的子节点", null, 0));
        Long first = volunteer(true, 0, "13900001111");
        Long second = volunteer(true, 0, "13900002222");
        structureService.addMember(dept, memberSorted(second, "副部长", 2));
        structureService.addMember(dept, memberSorted(first, "部长", 1));

        StructureNodeVO withPhone = find(structureService.tree(true), dept);
        assertNotNull(withPhone, "建的节点在树里");
        assertEquals(1, withPhone.getChildren().size());
        assertEquals(child, withPhone.getChildren().get(0).getId());
        assertEquals(2, withPhone.getMembers().size());
        StructureNodeVO.Member top = withPhone.getMembers().get(0);
        assertEquals(first, top.getVolunteerId(), "节点内按排序");
        assertEquals("部长", top.getPosition());
        assertEquals("测试志愿者", top.getName());
        assertEquals("13900001111", top.getPhone());

        StructureNodeVO noPhone = find(structureService.tree(false), dept);
        assertTrue(noPhone.getMembers().stream().allMatch(x -> x.getPhone() == null), "不该看电话的人拿不到电话");
        assertEquals("部长", noPhone.getMembers().get(0).getPosition());
    }

    // ---------------------------------------------------------------------

    private Long rootId() {
        return nodeMapper.selectOne(Wrappers.<OrganizationStructureNode>lambdaQuery()
                .isNull(OrganizationStructureNode::getParentId)).getId();
    }

    private static StructureNodeVO find(List<StructureNodeVO> nodes, Long id) {
        for (StructureNodeVO n : nodes) {
            if (n.getId().equals(id)) {
                return n;
            }
            StructureNodeVO hit = find(n.getChildren(), id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private Long volunteer(boolean registered, int status, String phone) {
        Volunteer v = new Volunteer();
        v.setOpenid("test:structure:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setRealName("测试志愿者");
        v.setStatus(status);
        v.setManagerFlag(0);
        if (registered) {
            v.setRegisterTime(LocalDateTime.now());
        }
        if (phone != null) {
            v.setPhone(cryptoUtil.encrypt(phone));
        }
        volunteerMapper.insert(v);
        return v.getId();
    }

    static StructureDTOs.NodeSave node(Long parentId, String name, String title, Integer sort) {
        StructureDTOs.NodeSave d = new StructureDTOs.NodeSave();
        d.setParentId(parentId);
        d.setName(name);
        d.setTitle(title);
        d.setSort(sort);
        return d;
    }

    static StructureDTOs.MemberSave member(Long volunteerId, String position) {
        return memberSorted(volunteerId, position, null);
    }

    static StructureDTOs.MemberSave memberSorted(Long volunteerId, String position, Integer sort) {
        StructureDTOs.MemberSave d = new StructureDTOs.MemberSave();
        d.setVolunteerId(volunteerId);
        d.setPosition(position);
        d.setSort(sort);
        return d;
    }

    private static void assertMessage(String fragment, Executable call) {
        BusinessException e = assertThrows(BusinessException.class, call);
        assertTrue(e.getMessage().contains(fragment), "期望提示含「" + fragment + "」，实际：" + e.getMessage());
    }
}
