package com.hengde.organization.permission;

import cn.dev33.satoken.stp.StpInterface;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.entity.AdminUser;
import com.hengde.organization.constant.PermissionCode;
import com.hengde.organization.dao.AdminPermissionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 权限数据源。{@code @SaCheckPermission} 鉴权时由 Sa-Token 回调本类取权限集。
 *
 * <p>只对管理端（loginType={@code admin}）供权限：
 * <ul>
 *   <li>超管（{@code is_super_admin=1}）→ 返回 {@code *} 万能码，放行所有 {@code @SaCheckPermission}；</li>
 *   <li>普通子账号 → 返回其 {@code admin_permission} 关联的权限点编码集合。</li>
 * </ul>
 * 账号被禁用（{@code status!=0}）一律返回空集——即便 token 未过期也拿不到任何权限点，
 * 使所有 {@code @SaCheckPermission} 拒绝，避免「停用但 token 仍在」的越权窗口（注销账号经
 * {@code @TableLogic} 已被 {@code selectById} 过滤为 null，同样返回空）。
 * 志愿者端（loginType={@code login}）不走权限点体系，返回空集。角色体系 V1 不用，{@link #getRoleList} 返回空。</p>
 *
 * @author hengde
 */
@Component
public class AdminStpInterface implements StpInterface {

    private AdminUserMapper adminUserMapper;
    private AdminPermissionMapper adminPermissionMapper;

    @Autowired
    public void setAdminUserMapper(AdminUserMapper adminUserMapper) {
        this.adminUserMapper = adminUserMapper;
    }

    @Autowired
    public void setAdminPermissionMapper(AdminPermissionMapper adminPermissionMapper) {
        this.adminPermissionMapper = adminPermissionMapper;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        if (!StpAdminUtil.TYPE.equals(loginType)) {
            return Collections.emptyList();
        }
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

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return Collections.emptyList();
    }
}
