package com.hengde.organization;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.AdminUser;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.dao.PermissionMapper;
import com.hengde.organization.dao.VolunteerPermissionMapper;
import com.hengde.organization.entity.Permission;
import com.hengde.organization.entity.VolunteerPermission;
import com.hengde.organization.permission.AdminStpInterface;
import com.hengde.organization.service.PermissionService;
import com.hengde.organization.service.VolunteerPermissionService;
import com.hengde.organization.vo.PermissionVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 志愿者端 RBAC（V18）验证：StpInterface 志愿者分支 + 白名单授权 + 仅超管。
 *
 * <p>schema/预置数据来自 common 的 Flyway 迁移，在 Testcontainers MySQL 建库时执行。<b>需本机有 Docker。</b>
 * 仿 {@code OrganizationRbacTest} 直接调 StpInterface / service，不依赖 Sa-Token 请求上下文。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class VolunteerPermissionRbacTest {

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private PermissionMapper permissionMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private AdminUserMapper adminUserMapper;
    @Autowired
    private VolunteerPermissionMapper volunteerPermissionMapper;
    @Autowired
    private VolunteerPermissionService volunteerPermissionService;
    @Autowired
    private PermissionService permissionService;
    @Autowired
    private AdminStpInterface adminStpInterface;

    @Test
    void grantableCatalogIsActivitySubsetWithoutMenu() {
        List<PermissionVO> grantable = permissionService.listGrantableToVolunteer();
        assertEquals(16, grantable.size(), "本期开放给志愿者的应为活动域 16 点（17 减去 activity:menu）");
        assertTrue(grantable.stream().allMatch(p -> "activity".equals(p.getModule())), "仅活动域");
        assertTrue(grantable.stream().anyMatch(p -> "activity:publish".equals(p.getCode())));
        assertFalse(grantable.stream().anyMatch(p -> "activity:menu".equals(p.getCode())), "活动管理菜单不开放给志愿者");
    }

    @Test
    void activeManagerGetsAssignedCodes() {
        Long vid = insertManager();
        insertVolunteerPermission(vid, permId("activity:publish"));

        List<String> codes = adminStpInterface.getPermissionList(vid, "login");
        assertEquals(List.of("activity:publish"), codes, "活跃的管理团队志愿者应返回已分配的活动权限点");
    }

    @Test
    void suspendedVolunteerGetsEmpty() {
        Long vid = insertVolunteer(1, 1); // 停用的「管理团队」志愿者——隔离出「停用」这一单因
        insertVolunteerPermission(vid, permId("activity:publish"));

        assertTrue(adminStpInterface.getPermissionList(vid, "login").isEmpty(),
                "停用志愿者即便有授权行、且仍标记管理团队，也拿不到任何权限点");
    }

    @Test
    void suspendedVolunteerMyCodesEmpty() {
        // my-permissions 须与 StpInterface「停用返空」同口径，避免停用 token 仍拿到码让前端误显入口
        Long vid = insertVolunteer(1, 1); // 停用的「管理团队」志愿者
        insertVolunteerPermission(vid, permId("activity:publish"));

        assertTrue(volunteerPermissionService.myCodes(vid).isEmpty(),
                "停用志愿者 myCodes 也应返回空（与 StpInterface 口径一致）");
    }

    @Test
    void mapperFiltersNonGrantablePermission() {
        // 即便脏行授了非白名单权限（如删志愿者），也不应经志愿者 token 外泄
        Long vid = insertVolunteer(0);
        insertVolunteerPermission(vid, permId("user:delete"));

        assertTrue(volunteerPermissionMapper.selectCodesByVolunteerId(vid).isEmpty(),
                "selectCodesByVolunteerId 应按 volunteer_grantable=1 过滤掉非白名单点");
    }

    @Test
    void superAdminAssignsGrantablePermission() {
        Long saId = insertAdmin(1);
        Long vid = insertManager();

        volunteerPermissionService.assignPermissionsBy(vid, List.of(permId("activity:publish")), saId);

        assertEquals(List.of("activity:publish"), volunteerPermissionService.myCodes(vid));
    }

    @Test
    void assignRejectsNonGrantablePermission() {
        Long saId = insertAdmin(1);
        Long vid = insertManager();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                volunteerPermissionService.assignPermissionsBy(vid, List.of(permId("user:delete")), saId));
        assertTrue(ex.getMessage().contains("不可授权给志愿者"));
    }

    @Test
    void nonSuperAdminCannotAssign() {
        Long subId = insertAdmin(0); // 非超管
        Long vid = insertVolunteer(0);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                volunteerPermissionService.assignPermissionsBy(vid, List.of(permId("activity:publish")), subId));
        assertTrue(ex.getMessage().contains("超级管理员"));
    }

    @Test
    void superAdminCanReadAssigned() {
        Long saId = insertAdmin(1);
        Long vid = insertManager();
        volunteerPermissionService.assignPermissionsBy(vid, List.of(permId("activity:publish")), saId);

        List<PermissionVO> assigned = volunteerPermissionService.listAssignedBy(vid, saId);
        assertEquals(1, assigned.size());
        assertEquals("activity:publish", assigned.get(0).getCode(), "超管可读志愿者已授权限");
    }

    @Test
    void nonSuperAdminCannotReadAssigned() {
        // 读「已授权限」与写授权同一超管边界，防被误改回 org:manager-flag 可读
        Long subId = insertAdmin(0); // 非超管
        Long vid = insertManager();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                volunteerPermissionService.listAssignedBy(vid, subId));
        assertTrue(ex.getMessage().contains("超级管理员"));
    }

    @Test
    void assignRejectsNonManagerVolunteer() {
        Long saId = insertAdmin(1);
        Long vid = insertVolunteer(0); // 活跃但未标记「管理团队」

        BusinessException ex = assertThrows(BusinessException.class, () ->
                volunteerPermissionService.assignPermissionsBy(vid, List.of(permId("activity:publish")), saId));
        assertTrue(ex.getMessage().contains("管理团队"),
                "未标记管理团队的志愿者不可被授权（防误授普通/游客态志愿者发活动）");
    }

    @Test
    void downgradedManagerLosesPermissionsImmediately() {
        Long saId = insertAdmin(1);
        Long vid = insertManager();
        volunteerPermissionService.assignPermissionsBy(vid, List.of(permId("activity:publish")), saId);
        assertEquals(List.of("activity:publish"), adminStpInterface.getPermissionList(vid, "login"));

        setManagerFlag(vid, 0); // 取消管理团队标记 = 降级

        assertTrue(adminStpInterface.getPermissionList(vid, "login").isEmpty(),
                "降级后 StpInterface 应立即返空（stale 授权不再生效）");
        assertTrue(volunteerPermissionService.myCodes(vid).isEmpty(), "myCodes 同步降级即失效");
    }

    @Test
    void clearPermissionsAllowedAfterDowngrade() {
        Long saId = insertAdmin(1);
        Long vid = insertManager();
        volunteerPermissionService.assignPermissionsBy(vid, List.of(permId("activity:publish")), saId);
        setManagerFlag(vid, 0); // 降级

        // 降级后传空列表清空 stale 授权——不被 manager 门槛拒
        volunteerPermissionService.assignPermissionsBy(vid, List.of(), saId);

        assertTrue(volunteerPermissionMapper.selectCodesByVolunteerId(vid).isEmpty(),
                "降级后传空 permissionIds 应清空旧授权，不被 manager_flag 门槛挡住");
    }

    // ---------- helpers ----------

    private Long permId(String code) {
        Permission p = permissionMapper.selectOne(
                Wrappers.<Permission>lambdaQuery().eq(Permission::getCode, code));
        return p.getId();
    }

    private Long insertVolunteer(int status) {
        return insertVolunteer(status, null);
    }

    /** 活跃 + 已标记「管理团队」(manager_flag=1) 的志愿者——授权写入与读取门槛均要求此身份。 */
    private Long insertManager() {
        return insertVolunteer(0, 1);
    }

    private Long insertVolunteer(int status, Integer managerFlag) {
        Volunteer v = new Volunteer();
        v.setOpenid("test:perm:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setRealName("测试志愿者");
        v.setStatus(status);
        v.setManagerFlag(managerFlag);
        volunteerMapper.insert(v);
        return v.getId();
    }

    private void setManagerFlag(Long volunteerId, Integer flag) {
        Volunteer v = volunteerMapper.selectById(volunteerId);
        v.setManagerFlag(flag);
        volunteerMapper.updateById(v);
    }

    private Long insertAdmin(int isSuper) {
        AdminUser a = new AdminUser();
        a.setUsername("vp_" + System.nanoTime() + "_" + SEQ.incrementAndGet());
        a.setPassword("x");
        a.setIsSuperAdmin(isSuper);
        a.setStatus(0);
        adminUserMapper.insert(a);
        return a.getId();
    }

    private void insertVolunteerPermission(Long volunteerId, Long permissionId) {
        VolunteerPermission vp = new VolunteerPermission();
        vp.setVolunteerId(volunteerId);
        vp.setPermissionId(permissionId);
        volunteerPermissionMapper.insert(vp);
    }
}
