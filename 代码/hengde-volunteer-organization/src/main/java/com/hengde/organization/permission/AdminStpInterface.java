package com.hengde.organization.permission;

import cn.dev33.satoken.stp.StpInterface;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.entity.AdminUser;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.organization.constant.PermissionCode;
import com.hengde.organization.dao.AdminPermissionMapper;
import com.hengde.organization.dao.VolunteerPermissionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 权限数据源。{@code @SaCheckPermission} 鉴权时由 Sa-Token 回调本类取权限集。
 *
 * <p>管理端（loginType={@code admin}，{@link StpAdminUtil}）：
 * <ul>
 *   <li>超管（{@code is_super_admin=1}）→ 返回 {@code *} 万能码，放行所有 {@code @SaCheckPermission}；</li>
 *   <li>普通子账号 → 返回其 {@code admin_permission} 关联的权限点编码集合。</li>
 * </ul>
 * 志愿者端（loginType={@code login}，默认 {@code StpUtil}，V18 起）：返回其 {@code volunteer_permission} 关联的
 * 权限点编码集合（无 {@code *} 通配）——「管理团队」志愿者凭此在 {@code /v} 端管理/发布活动。</p>
 *
 * <p>两端口径一致：账号被禁用/注销/不存在一律返回空集——即便 token 未过期也拿不到任何权限点，
 * 使所有 {@code @SaCheckPermission} 拒绝，避免「停用但 token 仍在」的越权窗口。
 * 角色体系 V1 不用，{@link #getRoleList} 返回空。</p>
 *
 * @author hengde
 */
@Component
public class AdminStpInterface implements StpInterface {

    /** 志愿者端 Sa-Token 默认登录类型（{@code StpUtil} 的 loginType） */
    private static final String VOLUNTEER_LOGIN_TYPE = "login";

    private AdminUserMapper adminUserMapper;
    private AdminPermissionMapper adminPermissionMapper;
    private VolunteerPermissionMapper volunteerPermissionMapper;
    private VolunteerQueryService volunteerQueryService;

    @Autowired
    public void setAdminUserMapper(AdminUserMapper adminUserMapper) {
        this.adminUserMapper = adminUserMapper;
    }

    @Autowired
    public void setAdminPermissionMapper(AdminPermissionMapper adminPermissionMapper) {
        this.adminPermissionMapper = adminPermissionMapper;
    }

    @Autowired
    public void setVolunteerPermissionMapper(VolunteerPermissionMapper volunteerPermissionMapper) {
        this.volunteerPermissionMapper = volunteerPermissionMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        if (StpAdminUtil.TYPE.equals(loginType)) {
            return adminPermissions(loginId);
        }
        if (VOLUNTEER_LOGIN_TYPE.equals(loginType)) {
            return volunteerPermissions(loginId);
        }
        return Collections.emptyList();
    }

    /** 管理端权限：超管 → {@code *}；子账号 → 已分配权限点；禁用/不存在 → 空。 */
    private List<String> adminPermissions(Object loginId) {
        long adminId = Long.parseLong(loginId.toString());
        AdminUser admin = adminUserMapper.selectById(adminId);
        // 账号不存在/已注销，或被禁用（status!=0）→ 无任何权限
        if (admin == null || !Integer.valueOf(0).equals(admin.getStatus())) {
            return Collections.emptyList();
        }
        if (Integer.valueOf(1).equals(admin.getIsSuperAdmin())) {
            return Collections.singletonList(PermissionCode.WILDCARD);
        }
        return adminPermissionMapper.selectCodesByAdminId(adminId);
    }

    /** 志愿者端权限：活跃志愿者 → 已分配权限点（无通配）；停用/注销/不存在 → 空。 */
    private List<String> volunteerPermissions(Object loginId) {
        long volunteerId = Long.parseLong(loginId.toString());
        if (!volunteerQueryService.isActive(volunteerId)) {
            return Collections.emptyList();
        }
        return volunteerPermissionMapper.selectCodesByVolunteerId(volunteerId);
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return Collections.emptyList();
    }
}
