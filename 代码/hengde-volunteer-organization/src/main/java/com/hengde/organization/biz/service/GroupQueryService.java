package com.hengde.organization.biz.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.organization.biz.constant.MemberStatus;
import com.hengde.organization.biz.dao.VolunteerGroupMemberMapper;
import com.hengde.organization.biz.entity.VolunteerGroupMember;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 跨模块只读出参：供其他领域（如 activity 代报名）以「最小耦合」方式校验小组关系，
 * 不暴露 mapper/entity，业务语义内聚在本服务。
 *
 * <p>不写库，纯读校验；失败抛 {@link BusinessException} 由全局兜底。</p>
 *
 * @author hengde
 */
@Service
public class GroupQueryService {

    /** ACTIVE 成员状态（与 GroupService 共用同一来源） */
    private static final int MEMBER_ACTIVE = MemberStatus.ACTIVE;

    private VolunteerGroupMemberMapper memberMapper;

    @Autowired
    public void setMemberMapper(VolunteerGroupMemberMapper memberMapper) {
        this.memberMapper = memberMapper;
    }

    /**
     * 代报名前置校验：actor 与所有 targets 必须**在同一小组**且**全部 ACTIVE**。
     *
     * <p>语义细节：
     * <ul>
     *   <li>targets 可以包含 actor 本人——无副作用（actor 一定与自己同组、自己 ACTIVE）。</li>
     *   <li>actor 必须有一条 ACTIVE 成员记录；否则抛「未加入任何小组」。</li>
     *   <li>任意一个 target 不在 actor 所在小组、或不在 ACTIVE 状态、或根本不是该小组成员，
     *       立即抛业务异常，不暴露具体哪一个失败（防探测他人小组归属）。</li>
     * </ul></p>
     *
     * @param actorId   发起代报名的志愿者
     * @param targetIds 被代报名的志愿者列表（含或不含 actor 本人均可）
     * @return actor 所在小组 id（业务可用于落库/审计）
     */
    public Long requireSameActiveGroup(Long actorId, List<Long> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) {
            throw new BusinessException("请至少选择一名被代报名的同组成员");
        }
        // actor 的 ACTIVE 小组（一人一组 → 至多一条）
        VolunteerGroupMember actor = memberMapper.selectOne(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getVolunteerId, actorId)
                .eq(VolunteerGroupMember::getStatus, MEMBER_ACTIVE)
                .last("limit 1"));
        if (actor == null) {
            throw new BusinessException("您尚未加入任何小组，无法代报名");
        }
        Long groupId = actor.getGroupId();

        // 一次查所有 target 在 actor 小组里的 ACTIVE 记录
        Set<Long> distinctTargets = new HashSet<>(targetIds);
        List<VolunteerGroupMember> rows = memberMapper.selectList(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getGroupId, groupId)
                .eq(VolunteerGroupMember::getStatus, MEMBER_ACTIVE)
                .in(VolunteerGroupMember::getVolunteerId, distinctTargets));
        Set<Long> matched = new HashSet<>();
        for (VolunteerGroupMember m : rows) {
            matched.add(m.getVolunteerId());
        }
        if (!matched.containsAll(distinctTargets)) {
            // 不告知具体是谁，避免被用来探测他人小组归属
            throw new BusinessException("被代报名的同学必须与您在同一小组且为正式成员");
        }
        return groupId;
    }
}
