package com.hengde.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.entity.AdminUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 后台账号的跨模块只读查询（V4 投诉建议批）：部门、姓名。
 *
 * <p>与 {@link VolunteerQueryService} 同一个理由收在 auth：「账号启用」这条语义归 auth 一处说了算，
 * 不让 data / organization 各自去比 {@code admin_user.status}。</p>
 *
 * @author hengde
 */
@Service
public class AdminQueryService {

    /** admin_user.status：0 启用 / 1 禁用（V1）。 */
    private static final int ENABLED = 0;

    private AdminUserMapper adminUserMapper;

    @Autowired
    public void setAdminUserMapper(AdminUserMapper adminUserMapper) {
        this.adminUserMapper = adminUserMapper;
    }

    /** 这个后台账号所属部门（去首尾空白）；账号不存在或没填部门返回 null。 */
    public String departmentOf(Long adminId) {
        AdminUser a = adminId == null ? null : adminUserMapper.selectById(adminId);
        return a == null || !StringUtils.hasText(a.getDepartment()) ? null : a.getDepartment().trim();
    }

    /**
     * 当前<b>有启用账号</b>的部门（按字典序、去重、去空白）。
     *
     * <p>部门在本系统里只是 {@code admin_user.department} 上的一段文字，没有主数据表；
     * 「有启用账号」是「转过去有人看得到」的最低保证（V4规划 Q7 默认）。</p>
     */
    public List<String> activeDepartments() {
        return adminUserMapper.selectList(Wrappers.<AdminUser>lambdaQuery()
                        .select(AdminUser::getDepartment)
                        .eq(AdminUser::getStatus, ENABLED)
                        .isNotNull(AdminUser::getDepartment))
                .stream().map(AdminUser::getDepartment).filter(StringUtils::hasText).map(String::trim)
                .collect(Collectors.toCollection(TreeSet::new)).stream().toList();
    }

    /** 是不是超管（账号存在且 is_super_admin=1）。 */
    public boolean isSuperAdmin(Long adminId) {
        if (adminId == null) {
            return false;
        }
        AdminUser u = adminUserMapper.selectOne(Wrappers.<AdminUser>lambdaQuery()
                .select(AdminUser::getId, AdminUser::getIsSuperAdmin).eq(AdminUser::getId, adminId));
        return u != null && Integer.valueOf(1).equals(u.getIsSuperAdmin());
    }

    /** 后台账号 id → 手机号（明文列；没填的不在返回里），一次查库。供活动名单公示展示「后台账号负责人」的电话。 */
    public Map<Long, String> listPhonesByIds(Collection<Long> adminIds) {
        List<Long> ids = adminIds == null ? List.of() : adminIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return adminUserMapper.selectList(Wrappers.<AdminUser>lambdaQuery()
                        .select(AdminUser::getId, AdminUser::getPhone)
                        .in(AdminUser::getId, ids))
                .stream().filter(a -> StringUtils.hasText(a.getPhone()))
                .collect(Collectors.toMap(AdminUser::getId, AdminUser::getPhone));
    }

    /** 后台账号 id → 姓名（没填姓名时用登录名），一次查库。 */
    public Map<Long, String> listNamesByIds(Collection<Long> adminIds) {
        List<Long> ids = adminIds == null ? List.of() : adminIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return adminUserMapper.selectList(Wrappers.<AdminUser>lambdaQuery()
                        .select(AdminUser::getId, AdminUser::getRealName, AdminUser::getUsername)
                        .in(AdminUser::getId, ids))
                .stream().collect(Collectors.toMap(AdminUser::getId,
                        a -> StringUtils.hasText(a.getRealName()) ? a.getRealName() : a.getUsername()));
    }
}
