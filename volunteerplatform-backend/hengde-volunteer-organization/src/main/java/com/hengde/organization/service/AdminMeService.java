package com.hengde.organization.service;

import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.entity.AdminUser;
import com.hengde.common.exception.BusinessException;
import com.hengde.organization.constant.PermissionCode;
import com.hengde.organization.dao.AdminPermissionMapper;
import com.hengde.organization.vo.AdminMeVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 当前管理员资料 + 权限码查询（{@code GET /a/auth/me}）。
 *
 * <p>放在 organization 而非 auth：要同时读 auth 的 {@link AdminUser} 与 organization 的权限关联表，
 * 而 auth 不能反向依赖 organization。权限码口径与 {@link com.hengde.organization.permission.AdminStpInterface}
 * 一致——超管返回万能码 {@code *}，子账号返回已分配权限点。账号状态由 {@code /a/**} 路由守卫先行兜底
 * （禁用/注销会在到达本接口前被拦下）。</p>
 *
 * @author hengde
 */
@Service
public class AdminMeService {

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

    public AdminMeVO me(long adminId) {
        AdminUser admin = adminUserMapper.selectById(adminId);
        if (admin == null) {
            throw new BusinessException("账号不存在");
        }
        boolean superAdmin = Integer.valueOf(1).equals(admin.getIsSuperAdmin());
        AdminMeVO vo = new AdminMeVO();
        vo.setAdminId(admin.getId());
        vo.setUsername(admin.getUsername());
        vo.setRealName(admin.getRealName());
        vo.setDepartment(admin.getDepartment());
        vo.setSuperAdmin(superAdmin);
        vo.setPermissionCodes(superAdmin
                ? List.of(PermissionCode.WILDCARD)
                : adminPermissionMapper.selectCodesByAdminId(adminId));
        return vo;
    }
}
