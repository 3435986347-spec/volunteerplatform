package com.hengde.organization;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.entity.AdminUser;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.constant.PermissionCode;
import com.hengde.organization.dao.AdminPermissionMapper;
import com.hengde.organization.dao.PermissionMapper;
import com.hengde.organization.entity.AdminPermission;
import com.hengde.organization.entity.Permission;
import com.hengde.organization.permission.AdminStpInterface;
import com.hengde.organization.service.AdminMeService;
import com.hengde.organization.vo.AdminMeVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RBAC 验证：V2 预置权限点 + StpInterface 三条分支（超管 / 子账号 / 志愿者）。
 *
 * <p>schema 与预置数据来自 common 的 {@code db/migration}，由 Flyway 在 Testcontainers MySQL 建库时执行。
 * <b>需本机有 Docker。</b></p>
 *
 * @author hengde
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class OrganizationRbacTest {

    @Autowired
    private PermissionMapper permissionMapper;
    @Autowired
    private AdminUserMapper adminUserMapper;
    @Autowired
    private AdminPermissionMapper adminPermissionMapper;
    @Autowired
    private AdminStpInterface adminStpInterface;
    @Autowired
    private AdminMeService adminMeService;

    @Test
    void permissionsSeeded() {
        assertEquals(34L, permissionMapper.selectCount(null),
                "可分配权限点：V2 23 + V4 enroll-view + V10 活动 6 + V12 manager-flag + V16 补录 2 + V19 发布审核 1 = 34");
    }

    @Test
    void superAdminGetsWildcard() {
        AdminUser sa = new AdminUser();
        sa.setUsername("sa_" + System.currentTimeMillis());
        sa.setPassword("x");
        sa.setIsSuperAdmin(1);
        sa.setStatus(0);
        adminUserMapper.insert(sa);

        List<String> perms = adminStpInterface.getPermissionList(sa.getId(), "admin");
        assertEquals(List.of(PermissionCode.WILDCARD), perms, "超管应拿到 * 万能码");
    }

    @Test
    void subAccountGetsAssignedCodes() {
        AdminUser sub = new AdminUser();
        sub.setUsername("sub_" + System.currentTimeMillis());
        sub.setPassword("x");
        sub.setIsSuperAdmin(0);
        sub.setStatus(0);
        adminUserMapper.insert(sub);

        Permission perm = permissionMapper.selectOne(
                Wrappers.<Permission>lambdaQuery().eq(Permission::getCode, PermissionCode.ORG_SUB_ACCOUNT));
        AdminPermission ap = new AdminPermission();
        ap.setAdminUserId(sub.getId());
        ap.setPermissionId(perm.getId());
        adminPermissionMapper.insert(ap);

        List<String> perms = adminStpInterface.getPermissionList(sub.getId(), "admin");
        assertEquals(List.of(PermissionCode.ORG_SUB_ACCOUNT), perms, "子账号应拿到已分配的权限点");
    }

    @Test
    void volunteerLoginTypeGetsEmpty() {
        assertTrue(adminStpInterface.getPermissionList(1L, "login").isEmpty(),
                "志愿者端不走权限点体系，应为空集");
    }

    // ---------- GET /a/auth/me：当前管理员资料 + 权限码 ----------

    @Test
    void me_superAdmin_returnsWildcardAndProfile() {
        AdminUser sa = new AdminUser();
        sa.setUsername("me_sa_" + System.currentTimeMillis());
        sa.setPassword("x");
        sa.setRealName("超管张三");
        sa.setDepartment("理事会");
        sa.setIsSuperAdmin(1);
        sa.setStatus(0);
        adminUserMapper.insert(sa);

        AdminMeVO me = adminMeService.me(sa.getId());
        assertEquals(sa.getId(), me.getAdminId());
        assertEquals("超管张三", me.getRealName());
        assertEquals("理事会", me.getDepartment());
        assertTrue(me.getSuperAdmin(), "isSuperAdmin 应为 true");
        assertEquals(List.of(PermissionCode.WILDCARD), me.getPermissionCodes(), "超管权限码应为 [*]");
    }

    @Test
    void me_subAccount_returnsAssignedCodesNotWildcard() {
        AdminUser sub = new AdminUser();
        sub.setUsername("me_sub_" + System.currentTimeMillis());
        sub.setPassword("x");
        sub.setRealName("组织部李四");
        sub.setDepartment("组织部");
        sub.setIsSuperAdmin(0);
        sub.setStatus(0);
        adminUserMapper.insert(sub);

        Permission perm = permissionMapper.selectOne(
                Wrappers.<Permission>lambdaQuery().eq(Permission::getCode, PermissionCode.ORG_GROUP_AUDIT));
        AdminPermission ap = new AdminPermission();
        ap.setAdminUserId(sub.getId());
        ap.setPermissionId(perm.getId());
        adminPermissionMapper.insert(ap);

        AdminMeVO me = adminMeService.me(sub.getId());
        assertFalse(me.getSuperAdmin(), "子账号 isSuperAdmin 应为 false");
        assertEquals(List.of(PermissionCode.ORG_GROUP_AUDIT), me.getPermissionCodes(),
                "子账号应返回已分配权限点（非万能码）");
    }
}
