package com.hengde.organization.biz.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.constant.UserStatus;
import com.hengde.organization.biz.constant.MemberRole;
import com.hengde.organization.biz.constant.MemberStatus;
import com.hengde.organization.biz.dao.OrganizationStructureMemberMapper;
import com.hengde.organization.biz.dao.VolunteerGroupMemberMapper;
import com.hengde.organization.biz.entity.VolunteerGroupMember;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 注销落定之后，让出他在组织里占着的位置（协会 2026-09-16：注销＝退出协会）。
 *
 * <ul>
 *   <li><b>组织架构</b>：移出（{@link StructureService#removeVolunteer}）——架构图上不该还挂着退出协会的人；</li>
 *   <li><b>小组</b>：待审核与在册的成员身份置为「已退出」，<b>组长除外</b>——组长走了小组要有人接手，
 *       自动转给谁是管理决定，由管理员在后台转移组长；</li>
 *   <li><b>归属分队</b>：auth 落定时已经清掉 {@code volunteer.squad_id}；这里只为改造前就已注销的账号兜底。</li>
 * </ul>
 *
 * <p>由 {@code VolunteerDeactivatedListener}（事件，及时）与 {@code DeactivationCleanupJob}（定时，兜底）两条路调用，
 * <b>幂等</b>，只对确实已注销的账号生效。账号被理事会恢复后，这些位置不会跟着回来。</p>
 *
 * <p>写入一律 <b>REQUIRES_NEW</b>：事件路径是在注销事务提交之后的回调里跑的，默认传播会加入那个已提交的事务。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class DeactivationCleanupService {

    private StructureService structureService;
    private VolunteerGroupMemberMapper groupMemberMapper;
    private OrganizationStructureMemberMapper structureMemberMapper;
    private VolunteerMapper volunteerMapper;
    private TransactionTemplate requiresNewTemplate;

    @Autowired
    public void setStructureService(StructureService structureService) {
        this.structureService = structureService;
    }

    @Autowired
    public void setGroupMemberMapper(VolunteerGroupMemberMapper groupMemberMapper) {
        this.groupMemberMapper = groupMemberMapper;
    }

    @Autowired
    public void setStructureMemberMapper(OrganizationStructureMemberMapper structureMemberMapper) {
        this.structureMemberMapper = structureMemberMapper;
    }

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 让出一个已注销志愿者在架构、小组、分队里的位置。账号不是已注销（含已被恢复）时什么也不做。
     *
     * @return 是否真的动了什么
     */
    public boolean cleanup(Long volunteerId) {
        Volunteer v = volunteerId == null ? null : volunteerMapper.selectById(volunteerId);
        if (v == null || !UserStatus.DELETED.equals(v.getStatus())) {
            return false;
        }
        int structure = structureService.removeVolunteer(volunteerId);
        Integer groups = requiresNewTemplate.execute(s -> leaveGroups(volunteerId));
        Integer squad = requiresNewTemplate.execute(s -> volunteerMapper.update(null, Wrappers.<Volunteer>lambdaUpdate()
                .eq(Volunteer::getId, volunteerId)
                .eq(Volunteer::getStatus, UserStatus.DELETED)
                .isNotNull(Volunteer::getSquadId)
                .set(Volunteer::getSquadId, null)
                .set(Volunteer::getUpdateTime, LocalDateTime.now())));
        boolean changed = structure > 0 || (groups != null && groups > 0) || (squad != null && squad > 0);
        if (changed) {
            log.info("[DEACTIVATE] 让出组织位置 volunteerId={} 架构={} 小组={} 分队={}", volunteerId, structure, groups, squad);
        }
        return changed;
    }

    /** 定时兜底：找出还占着位置的已注销账号逐个收拾。返回处理了几个人。 */
    public int cleanupAll() {
        Set<Long> ids = new LinkedHashSet<>(structureMemberMapper.selectDeactivatedVolunteerIds());
        ids.addAll(groupMemberMapper.selectDeactivatedVolunteerIds());
        volunteerMapper.selectList(Wrappers.<Volunteer>lambdaQuery()
                        .select(Volunteer::getId)
                        .eq(Volunteer::getStatus, UserStatus.DELETED)
                        .isNotNull(Volunteer::getSquadId)
                        .last("LIMIT 500"))
                .forEach(v -> ids.add(v.getId()));
        int n = 0;
        for (Long id : ids) {
            try {
                n += cleanup(id) ? 1 : 0;
            } catch (RuntimeException e) {
                log.error("[DEACTIVATE] 让出组织位置失败 volunteerId={}，下一轮再试", id, e);
            }
        }
        return n;
    }

    /** 先普通读出他的成员行、再按主键 CAS——不对 volunteer_id 做范围 UPDATE（零行时会留下间隙锁）。 */
    private int leaveGroups(Long volunteerId) {
        List<VolunteerGroupMember> rows = groupMemberMapper.selectList(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getVolunteerId, volunteerId)
                .in(VolunteerGroupMember::getStatus, MemberStatus.PENDING, MemberStatus.ACTIVE)
                .ne(VolunteerGroupMember::getRole, MemberRole.LEADER));
        int n = 0;
        for (VolunteerGroupMember m : rows) {
            n += groupMemberMapper.update(null, Wrappers.<VolunteerGroupMember>lambdaUpdate()
                    .eq(VolunteerGroupMember::getId, m.getId())
                    .in(VolunteerGroupMember::getStatus, MemberStatus.PENDING, MemberStatus.ACTIVE)
                    .set(VolunteerGroupMember::getStatus, MemberStatus.LEFT)
                    .set(VolunteerGroupMember::getUpdateTime, LocalDateTime.now()));
        }
        return n;
    }
}
