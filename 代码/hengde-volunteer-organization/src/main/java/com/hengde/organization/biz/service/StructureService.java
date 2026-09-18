package com.hengde.organization.biz.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerContactView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.organization.biz.dao.OrganizationStructureMemberMapper;
import com.hengde.organization.biz.dao.OrganizationStructureNodeMapper;
import com.hengde.organization.biz.dto.StructureDTOs;
import com.hengde.organization.biz.entity.OrganizationStructureMember;
import com.hengde.organization.biz.entity.OrganizationStructureNode;
import com.hengde.organization.biz.vo.StructureNodeVO;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 组织架构（V5 起的节点树；V4 组织架构维护批起可维护、可放人——Row 6「由不同的小方框组成，插入志愿者之后显示他是哪个部门的什么职位，
 * 框架新增、减少，均有最高权限操作」，Row 5 F「修改部门功能，输入职位，前端名字下面展示」）。
 *
 * <p><b>所有写操作串行</b>（一把全局 Redisson 锁 {@code lock:org-structure}，锁在事务外）：挪节点要查「会不会把自己挂到自己的子孙下面」，
 * 删节点要查「还有没有子节点和人」，放人要查「节点还在不在」——这些都是「先查一下再动」，而架构维护一天也就几次，
 * 串行化的代价可以忽略，逐个配行锁反而容易漏一处（两个人同时把 A 挪到 B 下、把 B 挪到 A 下，就是一个环）。</p>
 *
 * <p><b>一个人在架构里至多一个位置</b>（{@code uk_active_volunteer}）；名字下面那一行「部门 · 职位」由这里现算
 * （{@link #positionLabelsOf}），V1 留在 {@code volunteer.position} 上的那一列不再写。</p>
 *
 * <p><b>电话</b>：Row 6 原文「展示名字、部门、职位、电话」——进了架构的人是协会的部门成员，电话全显示（V4规划 Q24）。
 * 只有架构里的人会被下发电话，普通志愿者不受影响；<b>看的人是游客（未实名）时不下发</b>。</p>
 *
 * @author hengde
 */
@Service
public class StructureService {

    private static final String LOCK_KEY = "lock:org-structure";
    private static final int MAX_DEPTH = 10;

    private OrganizationStructureNodeMapper nodeMapper;
    private OrganizationStructureMemberMapper memberMapper;
    private VolunteerQueryService volunteerQueryService;
    private RedissonClient redissonClient;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setNodeMapper(OrganizationStructureNodeMapper nodeMapper) {
        this.nodeMapper = nodeMapper;
    }

    @Autowired
    public void setMemberMapper(OrganizationStructureMemberMapper memberMapper) {
        this.memberMapper = memberMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ================= 读 =================

    /**
     * 架构树（含每个节点里的人：姓名 / 职位 / 电话）。
     *
     * @param includePhone 是否下发电话。后台恒为 true；志愿者端只有<b>已实名</b>的人才下发——游客用任何一个手机号收个验证码就能登录，
     *                     给游客下发等于把部门成员的电话公开在网上。
     */
    public List<StructureNodeVO> tree(boolean includePhone) {
        List<StructureNodeVO> nodes = nodeMapper.selectList(Wrappers.<OrganizationStructureNode>lambdaQuery()
                        .orderByAsc(OrganizationStructureNode::getSort)
                        .orderByAsc(OrganizationStructureNode::getId))
                .stream()
                .map(StructureService::toVO)
                .toList();
        List<OrganizationStructureMember> members = memberMapper.selectList(
                Wrappers.<OrganizationStructureMember>lambdaQuery()
                        .orderByAsc(OrganizationStructureMember::getSort)
                        .orderByAsc(OrganizationStructureMember::getId));
        Map<Long, VolunteerContactView> contacts = members.isEmpty() ? Map.of()
                : volunteerQueryService.listContactsByIds(
                        members.stream().map(OrganizationStructureMember::getVolunteerId).collect(Collectors.toSet()));
        Map<Long, List<StructureNodeVO.Member>> membersByNode = new HashMap<>();
        for (OrganizationStructureMember m : members) {
            VolunteerContactView c = contacts.get(m.getVolunteerId());
            StructureNodeVO.Member vo = new StructureNodeVO.Member();
            vo.setMemberId(m.getId());
            vo.setVolunteerId(m.getVolunteerId());
            vo.setName(c == null ? null : c.realName());
            vo.setPhone(c == null || !includePhone ? null : c.phone());
            vo.setPosition(m.getPosition());
            vo.setSort(m.getSort());
            membersByNode.computeIfAbsent(m.getNodeId(), k -> new ArrayList<>()).add(vo);
        }
        Map<Long, List<StructureNodeVO>> byParent = nodes.stream()
                .filter(node -> node.getParentId() != null)
                .collect(Collectors.groupingBy(StructureNodeVO::getParentId));
        for (StructureNodeVO node : nodes) {
            List<StructureNodeVO> children = new ArrayList<>(byParent.getOrDefault(node.getId(), List.of()));
            children.sort(Comparator.comparing(StructureNodeVO::getSort).thenComparing(StructureNodeVO::getId));
            node.setChildren(children);
            node.setMembers(membersByNode.getOrDefault(node.getId(), List.of()));
        }
        return nodes.stream().filter(node -> node.getParentId() == null).toList();
    }

    /**
     * 名字下面那一行「部门 · 职位」（Row 5 F），一批人一次查完；不在架构里的人不在返回里。
     * 给「我的资料」、志愿者管理列表 / 详情用。
     */
    public Map<Long, String> positionLabelsOf(Collection<Long> volunteerIds) {
        List<Long> ids = volunteerIds == null ? List.of() : volunteerIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<OrganizationStructureMember> members = memberMapper.selectList(Wrappers.<OrganizationStructureMember>lambdaQuery()
                .in(OrganizationStructureMember::getVolunteerId, ids));
        if (members.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> nodeNames = nodeMapper.selectList(Wrappers.<OrganizationStructureNode>lambdaQuery()
                        .select(OrganizationStructureNode::getId, OrganizationStructureNode::getName)
                        .in(OrganizationStructureNode::getId,
                                members.stream().map(OrganizationStructureMember::getNodeId).collect(Collectors.toSet())))
                .stream().collect(Collectors.toMap(OrganizationStructureNode::getId, OrganizationStructureNode::getName));
        Map<Long, String> out = new HashMap<>();
        for (OrganizationStructureMember m : members) {
            String dept = nodeNames.get(m.getNodeId());
            out.put(m.getVolunteerId(), dept == null ? m.getPosition() : dept + " · " + m.getPosition());
        }
        return out;
    }

    // ================= 节点 =================

    /** 新增节点：必须挂在一个现存节点下面（根只有一个，V5 种子里的协会）。 */
    public Long createNode(StructureDTOs.NodeSave dto) {
        String name = requireText(dto == null ? null : dto.getName(), "请填写名称", 64);
        if (dto.getParentId() == null) {
            throw new BusinessException("请选择上级节点（架构只有一个根）");
        }
        return locked(() -> {
            requireNode(dto.getParentId());
            if (depthOf(dto.getParentId()) + 1 >= MAX_DEPTH) {
                throw new BusinessException("架构层级不能超过 " + MAX_DEPTH + " 层");
            }
            OrganizationStructureNode n = new OrganizationStructureNode();
            n.setParentId(dto.getParentId());
            n.setName(name);
            n.setTitle(trimToNull(dto.getTitle(), 64));
            n.setSort(dto.getSort() == null ? 0 : dto.getSort());
            nodeMapper.insert(n);
            return n.getId();
        });
    }

    /** 修改节点（名称 / 说明 / 排序 / 上级）。换上级时不能挂到自己或自己的子孙下面；根节点不能换上级。 */
    public void updateNode(Long id, StructureDTOs.NodeSave dto) {
        String name = requireText(dto == null ? null : dto.getName(), "请填写名称", 64);
        locked(() -> {
            OrganizationStructureNode n = requireNode(id);
            Long parentId = dto.getParentId();
            if (n.getParentId() == null) {
                if (parentId != null) {
                    throw new BusinessException("根节点不能挂到别的节点下面");
                }
            } else {
                if (parentId == null) {
                    throw new BusinessException("请选择上级节点（架构只有一个根）");
                }
                if (!parentId.equals(n.getParentId())) {
                    requireNode(parentId);
                    if (parentId.equals(id) || ancestorsOf(parentId).contains(id)) {
                        throw new BusinessException("不能挂到自己或自己的下级节点下面");
                    }
                    if (depthOf(parentId) + 1 + heightOf(id) >= MAX_DEPTH) {
                        throw new BusinessException("架构层级不能超过 " + MAX_DEPTH + " 层");
                    }
                }
            }
            nodeMapper.update(null, Wrappers.<OrganizationStructureNode>lambdaUpdate()
                    .eq(OrganizationStructureNode::getId, id)
                    .set(OrganizationStructureNode::getParentId, n.getParentId() == null ? null : parentId)
                    .set(OrganizationStructureNode::getName, name)
                    .set(OrganizationStructureNode::getTitle, trimToNull(dto.getTitle(), 64))
                    .set(OrganizationStructureNode::getSort, dto.getSort() == null ? n.getSort() : dto.getSort())
                    .set(OrganizationStructureNode::getUpdateTime, LocalDateTime.now()));
            return null;
        });
    }

    /** 删除节点：根不能删；还有下级节点或人的不能删（先挪走——直接连带删掉会让人莫名其妙地从架构里消失）。 */
    public void deleteNode(Long id) {
        locked(() -> {
            OrganizationStructureNode n = requireNode(id);
            if (n.getParentId() == null) {
                throw new BusinessException("根节点不能删除");
            }
            Long children = nodeMapper.selectCount(Wrappers.<OrganizationStructureNode>lambdaQuery()
                    .eq(OrganizationStructureNode::getParentId, id));
            if (children != null && children > 0) {
                throw new BusinessException("「" + n.getName() + "」下面还有 " + children + " 个节点，请先挪走或删除");
            }
            Long members = memberMapper.selectCount(Wrappers.<OrganizationStructureMember>lambdaQuery()
                    .eq(OrganizationStructureMember::getNodeId, id));
            if (members != null && members > 0) {
                throw new BusinessException("「" + n.getName() + "」里还有 " + members + " 个人，请先挪走");
            }
            nodeMapper.deleteById(id);
            return null;
        });
    }

    // ================= 人 =================

    /** 把一位已实名的志愿者放进某个节点、写上职位。一个人在架构里只有一个位置，已在别处的请用「修改」挪过去。 */
    public Long addMember(Long nodeId, StructureDTOs.MemberSave dto) {
        String position = requireText(dto == null ? null : dto.getPosition(), "请填写职位", 64);
        Long volunteerId = dto.getVolunteerId();
        if (volunteerId == null) {
            throw new BusinessException("请选择志愿者");
        }
        if (!volunteerQueryService.filterActiveRegistered(List.of(volunteerId)).contains(volunteerId)) {
            throw new BusinessException("只能放入已实名且账号正常的志愿者");
        }
        return locked(() -> {
            requireNode(nodeId);
            OrganizationStructureMember m = new OrganizationStructureMember();
            m.setNodeId(nodeId);
            m.setVolunteerId(volunteerId);
            m.setPosition(position);
            m.setSort(dto.getSort() == null ? 0 : dto.getSort());
            try {
                memberMapper.insert(m);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("这位志愿者已经在架构里了，请用「修改」把他挪过去");
            }
            return m.getId();
        });
    }

    /** 修改职位 / 排序 / 挪到另一个节点。 */
    public void updateMember(Long memberId, StructureDTOs.MemberUpdate dto) {
        String position = requireText(dto == null ? null : dto.getPosition(), "请填写职位", 64);
        locked(() -> {
            OrganizationStructureMember m = requireMember(memberId);
            Long nodeId = dto.getNodeId() == null ? m.getNodeId() : dto.getNodeId();
            requireNode(nodeId);
            memberMapper.update(null, Wrappers.<OrganizationStructureMember>lambdaUpdate()
                    .eq(OrganizationStructureMember::getId, memberId)
                    .set(OrganizationStructureMember::getNodeId, nodeId)
                    .set(OrganizationStructureMember::getPosition, position)
                    .set(OrganizationStructureMember::getSort, dto.getSort() == null ? m.getSort() : dto.getSort())
                    .set(OrganizationStructureMember::getUpdateTime, LocalDateTime.now()));
            return null;
        });
    }

    public void removeMember(Long memberId) {
        locked(() -> {
            requireMember(memberId);
            memberMapper.deleteById(memberId);
            return null;
        });
    }

    // ================= 内部 =================

    private <T> T locked(Supplier<T> action) {
        return DistributedLockSupport.runLocked(redissonClient, LOCK_KEY,
                () -> transactionTemplate.execute(s -> action.get()));
    }

    private OrganizationStructureNode requireNode(Long id) {
        OrganizationStructureNode n = id == null ? null : nodeMapper.selectById(id);
        if (n == null) {
            throw new BusinessException("架构节点不存在");
        }
        return n;
    }

    private OrganizationStructureMember requireMember(Long id) {
        OrganizationStructureMember m = id == null ? null : memberMapper.selectById(id);
        if (m == null) {
            throw new BusinessException("架构里没有这个位置");
        }
        return m;
    }

    /** 从这个节点往上一直到根的全部祖先 id（不含自己）。遇到环（脏数据）就停，不死循环。 */
    private Set<Long> ancestorsOf(Long id) {
        Set<Long> seen = new HashSet<>();
        Long cur = requireNode(id).getParentId();
        while (cur != null && seen.add(cur)) {
            OrganizationStructureNode n = nodeMapper.selectById(cur);
            cur = n == null ? null : n.getParentId();
        }
        return seen;
    }

    /** 根的深度为 0。 */
    private int depthOf(Long id) {
        return ancestorsOf(id).size();
    }

    /** 以这个节点为根的子树高度（叶子为 0）。 */
    private int heightOf(Long id) {
        List<OrganizationStructureNode> all = nodeMapper.selectList(Wrappers.<OrganizationStructureNode>lambdaQuery()
                .select(OrganizationStructureNode::getId, OrganizationStructureNode::getParentId));
        Map<Long, List<Long>> children = new HashMap<>();
        for (OrganizationStructureNode n : all) {
            if (n.getParentId() != null) {
                children.computeIfAbsent(n.getParentId(), k -> new ArrayList<>()).add(n.getId());
            }
        }
        return height(id, children, new HashSet<>());
    }

    private static int height(Long id, Map<Long, List<Long>> children, Set<Long> seen) {
        if (!seen.add(id)) {
            return 0;
        }
        int h = 0;
        for (Long c : children.getOrDefault(id, List.of())) {
            h = Math.max(h, 1 + height(c, children, seen));
        }
        return h;
    }

    private static String requireText(String s, String message, int max) {
        if (!StringUtils.hasText(s)) {
            throw new BusinessException(message);
        }
        String t = s.trim();
        if (t.length() > max) {
            throw new BusinessException(message.replace("请填写", "") + "不超过 " + max + " 字");
        }
        return t;
    }

    private static String trimToNull(String s, int max) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        String t = s.trim();
        if (t.length() > max) {
            throw new BusinessException("说明不超过 " + max + " 字");
        }
        return t;
    }

    private static StructureNodeVO toVO(OrganizationStructureNode node) {
        StructureNodeVO vo = new StructureNodeVO();
        vo.setId(node.getId());
        vo.setParentId(node.getParentId());
        vo.setName(node.getName());
        vo.setTitle(node.getTitle());
        vo.setSort(node.getSort());
        return vo;
    }
}
