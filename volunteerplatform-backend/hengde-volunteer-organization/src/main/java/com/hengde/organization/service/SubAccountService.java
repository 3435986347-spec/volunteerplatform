package com.hengde.organization.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.entity.AdminUser;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.utils.PasswordUtil;
import com.hengde.organization.dao.AdminPermissionMapper;
import com.hengde.organization.dao.PermissionMapper;
import com.hengde.organization.dto.SubAccountCreateDTO;
import com.hengde.organization.dto.SubAccountUpdateDTO;
import com.hengde.organization.entity.AdminPermission;
import com.hengde.organization.vo.SubAccountVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 子账号与权限管理。
 *
 * <p>常规增删改查由 {@code @SaCheckPermission("org:sub-account")} 控制；
 * <b>权限分配</b>（{@link #assignPermissions}）不挂注解、写死仅超管，防子账号自助提权（R67）。</p>
 *
 * @author hengde
 */
@Service
public class SubAccountService {

    private AdminUserMapper adminUserMapper;
    private AdminPermissionMapper adminPermissionMapper;
    private PermissionMapper permissionMapper;

    @Autowired
    public void setAdminUserMapper(AdminUserMapper adminUserMapper) {
        this.adminUserMapper = adminUserMapper;
    }

    @Autowired
    public void setAdminPermissionMapper(AdminPermissionMapper adminPermissionMapper) {
        this.adminPermissionMapper = adminPermissionMapper;
    }

    @Autowired
    public void setPermissionMapper(PermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
    }

    public PageResult<SubAccountVO> list(PageQuery query, String keyword) {
        Page<AdminUser> page = query.toPage();
        var wrapper = Wrappers.<AdminUser>lambdaQuery()
                .eq(AdminUser::getIsSuperAdmin, 0);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(AdminUser::getUsername, keyword)
                    .or().like(AdminUser::getRealName, keyword)
                    .or().like(AdminUser::getPhone, keyword)
                    .or().like(AdminUser::getDepartment, keyword));
        }
        wrapper.orderByDesc(AdminUser::getCreateTime);
        adminUserMapper.selectPage(page, wrapper);

        List<SubAccountVO> records = page.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    public SubAccountVO detail(Long id) {
        AdminUser admin = getSubAccountOrThrow(id);
        SubAccountVO vo = toVO(admin);
        vo.setPermissionCodes(adminPermissionMapper.selectCodesByAdminId(id));
        return vo;
    }

    public Long create(SubAccountCreateDTO dto) {
        Long exists = adminUserMapper.selectCount(
                Wrappers.<AdminUser>lambdaQuery().eq(AdminUser::getUsername, dto.getUsername()));
        if (exists != null && exists > 0) {
            throw new BusinessException("登录账号已存在");
        }
        AdminUser admin = new AdminUser();
        admin.setUsername(dto.getUsername());
        admin.setPassword(PasswordUtil.encrypt(dto.getPassword()));
        admin.setRealName(dto.getRealName());
        admin.setPhone(dto.getPhone());
        admin.setDepartment(dto.getDepartment());
        admin.setIsSuperAdmin(0);
        admin.setStatus(0);
        adminUserMapper.insert(admin);
        return admin.getId();
    }

    public void update(Long id, SubAccountUpdateDTO dto) {
        AdminUser admin = getSubAccountOrThrow(id);
        admin.setRealName(dto.getRealName());
        admin.setPhone(dto.getPhone());
        admin.setDepartment(dto.getDepartment());
        adminUserMapper.updateById(admin);
    }

    @Transactional
    public void delete(Long id) {
        getSubAccountOrThrow(id);
        adminPermissionMapper.physicalDeleteByAdminId(id);
        adminUserMapper.deleteById(id);
    }

    public void resetPassword(Long id, String newPassword) {
        AdminUser admin = getSubAccountOrThrow(id);
        admin.setPassword(PasswordUtil.encrypt(newPassword));
        adminUserMapper.updateById(admin);
    }

    /**
     * 全量替换子账号权限。<b>仅超管可调用</b>（防自助提权），故在此手写校验而非走注解。
     */
    @Transactional
    public void assignPermissions(Long id, List<Long> permissionIds) {
        requireSuperAdmin();
        getSubAccountOrThrow(id);
        adminPermissionMapper.physicalDeleteByAdminId(id);
        if (CollectionUtils.isEmpty(permissionIds)) {
            return;
        }
        List<Long> distinctIds = permissionIds.stream().distinct().toList();
        long validCount = permissionMapper.selectCount(
                Wrappers.<com.hengde.organization.entity.Permission>lambdaQuery()
                        .in(com.hengde.organization.entity.Permission::getId, distinctIds));
        if (validCount != distinctIds.size()) {
            throw new BusinessException("包含无效的权限点");
        }
        for (Long permissionId : distinctIds) {
            AdminPermission ap = new AdminPermission();
            ap.setAdminUserId(id);
            ap.setPermissionId(permissionId);
            adminPermissionMapper.insert(ap);
        }
    }

    private void requireSuperAdmin() {
        long meId = StpAdminUtil.getLoginIdAsLong();
        AdminUser me = adminUserMapper.selectById(meId);
        // 必须是「启用中的超管」：注销(null)/禁用(status!=0)/非超管 一律拒绝
        if (me == null || !Integer.valueOf(0).equals(me.getStatus())
                || !Integer.valueOf(1).equals(me.getIsSuperAdmin())) {
            throw new BusinessException("仅超级管理员可分配权限");
        }
    }

    private AdminUser getSubAccountOrThrow(Long id) {
        AdminUser admin = adminUserMapper.selectById(id);
        if (admin == null) {
            throw new BusinessException("子账号不存在");
        }
        if (Integer.valueOf(1).equals(admin.getIsSuperAdmin())) {
            throw new BusinessException("超级管理员不可作为子账号管理");
        }
        return admin;
    }

    private SubAccountVO toVO(AdminUser admin) {
        SubAccountVO vo = new SubAccountVO();
        vo.setId(admin.getId());
        vo.setUsername(admin.getUsername());
        vo.setRealName(admin.getRealName());
        vo.setPhone(admin.getPhone());
        vo.setDepartment(admin.getDepartment());
        vo.setStatus(admin.getStatus());
        vo.setLastLoginTime(admin.getLastLoginTime());
        vo.setCreateTime(admin.getCreateTime());
        return vo;
    }
}
