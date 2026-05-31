package com.hengde.organization.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.entity.AdminUser;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.organization.dao.PermissionMapper;
import com.hengde.organization.dao.VolunteerPermissionMapper;
import com.hengde.organization.entity.Permission;
import com.hengde.organization.entity.VolunteerPermission;
import com.hengde.organization.vo.PermissionVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 志愿者端权限分配（RBAC）：给「管理团队」志愿者授权，供 {@code /v} 活动动作消费
 * （如发布活动 {@code POST /v/activity/activities}）。
 *
 * <p>口径对齐 {@link SubAccountService#assignPermissions}：<b>仅超管</b>可分配（手写校验、不挂注解，防自助提权），
 * 全量替换式维护。两道校验：<b>目标须已标记「管理团队」</b>（{@code manager_flag=1}；避免把发活动等权限误授给普通 /
 * 游客态志愿者，该标记本身只能给已实名者设，故已实名亦被覆盖）+ <b>白名单</b>（只接受
 * {@code permission.volunteer_grantable=1} 的点，本期=活动域子集，杜绝把删志愿者 / 子账号 / 分队等纯后台权限授给 C 端志愿者）。
 * 二者<b>仅在非空授权时校验</b>：传空 {@code permissionIds}=清空，始终放行（便于降级/停用后清理 stale 授权）。</p>
 *
 * <p>仿 {@code GroupService.joinApplicationsBy} 的可测性约定：对外提供读 {@code StpAdminUtil} 的便捷入口，
 * 另留显式操作人 {@link #assignPermissionsBy} 入口供测试 / 需绕过 Sa-Token 上下文的场景。</p>
 *
 * @author hengde
 */
@Service
public class VolunteerPermissionService {

    private AdminUserMapper adminUserMapper;
    private PermissionMapper permissionMapper;
    private VolunteerPermissionMapper volunteerPermissionMapper;
    private VolunteerQueryService volunteerQueryService;

    @Autowired
    public void setAdminUserMapper(AdminUserMapper adminUserMapper) {
        this.adminUserMapper = adminUserMapper;
    }

    @Autowired
    public void setPermissionMapper(PermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
    }

    @Autowired
    public void setVolunteerPermissionMapper(VolunteerPermissionMapper volunteerPermissionMapper) {
        this.volunteerPermissionMapper = volunteerPermissionMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    /**
     * 当前志愿者的权限码集合（供 /v my-permissions；前端据此显示/隐藏管理团队入口）。
     *
     * <p>与 {@code AdminStpInterface} 志愿者分支同口径：仅<b>活跃且为管理团队</b>（{@code manager_flag=1}）返回——
     * 停用/降级的志愿者返回空，避免前端误显入口（停用 token 的后续动作虽也会被 {@code @SaCheckPermission} 拒）。</p>
     */
    public List<String> myCodes(Long volunteerId) {
        if (!volunteerQueryService.isActiveManager(volunteerId)) {
            return List.of();
        }
        return volunteerPermissionMapper.selectCodesByVolunteerId(volunteerId);
    }

    /** 某志愿者已分配的权限点（后台回显，含完整 {@link PermissionVO}）。 */
    public List<PermissionVO> listAssigned(Long volunteerId) {
        List<VolunteerPermission> rows = volunteerPermissionMapper.selectList(
                Wrappers.<VolunteerPermission>lambdaQuery().eq(VolunteerPermission::getVolunteerId, volunteerId));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> permIds = rows.stream().map(VolunteerPermission::getPermissionId).distinct().toList();
        return permissionMapper.selectList(Wrappers.<Permission>lambdaQuery()
                        .in(Permission::getId, permIds).orderByAsc(Permission::getSort))
                .stream().map(this::toVO).toList();
    }

    /** 全量替换志愿者权限（操作人取当前管理端登录态）。 */
    public void assignPermissions(Long volunteerId, List<Long> permissionIds) {
        assignPermissionsBy(volunteerId, permissionIds, StpAdminUtil.getLoginIdAsLong());
    }

    /**
     * 全量替换志愿者权限（显式操作人）。仅超管可调用；只接受可授权给志愿者的白名单权限点。
     *
     * <p>白名单校验放在删除/写入之前——非法请求不会先清空既有授权。</p>
     */
    @Transactional
    public void assignPermissionsBy(Long volunteerId, List<Long> permissionIds, Long operatorAdminId) {
        requireSuperAdmin(operatorAdminId);
        List<Long> distinctIds = CollectionUtils.isEmpty(permissionIds)
                ? List.of() : permissionIds.stream().distinct().toList();
        // 授权（非空）才校验身份与白名单；清空（空列表）始终放行——便于降级/停用后清理 stale 授权，
        // 否则「降级即不能授权」会把已失效的 stale 行锁死、无法经本接口清除。
        if (!distinctIds.isEmpty()) {
            if (!volunteerQueryService.isActive(volunteerId)) {
                throw new BusinessException("志愿者不存在或已停用");
            }
            // 仅可给已标记「管理团队」的志愿者授权——否则普通/游客态志愿者就能凭权限发全平台活动。
            // manager_flag 本身只能给已实名者设（setManagerFlag 硬校验），故一并覆盖「须已实名」。
            if (!volunteerQueryService.isManager(volunteerId)) {
                throw new BusinessException("仅可给已标记「管理团队」的志愿者授权（请先设置 manager_flag）");
            }
            long grantableCount = permissionMapper.selectCount(Wrappers.<Permission>lambdaQuery()
                    .in(Permission::getId, distinctIds)
                    .eq(Permission::getVolunteerGrantable, 1));
            if (grantableCount != distinctIds.size()) {
                throw new BusinessException("包含不可授权给志愿者的权限点");
            }
        }
        volunteerPermissionMapper.physicalDeleteByVolunteerId(volunteerId);
        for (Long permissionId : distinctIds) {
            VolunteerPermission vp = new VolunteerPermission();
            vp.setVolunteerId(volunteerId);
            vp.setPermissionId(permissionId);
            volunteerPermissionMapper.insert(vp);
        }
    }

    private void requireSuperAdmin(Long operatorAdminId) {
        AdminUser me = operatorAdminId == null ? null : adminUserMapper.selectById(operatorAdminId);
        // 必须是「启用中的超管」：注销(null)/禁用(status!=0)/非超管 一律拒绝
        if (me == null || !Integer.valueOf(0).equals(me.getStatus())
                || !Integer.valueOf(1).equals(me.getIsSuperAdmin())) {
            throw new BusinessException("仅超级管理员可分配权限");
        }
    }

    private PermissionVO toVO(Permission p) {
        PermissionVO vo = new PermissionVO();
        vo.setId(p.getId());
        vo.setCode(p.getCode());
        vo.setName(p.getName());
        vo.setModule(p.getModule());
        vo.setType(p.getType());
        vo.setSort(p.getSort());
        return vo;
    }
}
