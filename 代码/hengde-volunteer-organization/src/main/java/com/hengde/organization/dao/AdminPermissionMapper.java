package com.hengde.organization.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.organization.entity.AdminPermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 子账号-权限点关联 Mapper。
 *
 * @author hengde
 */
@Mapper
public interface AdminPermissionMapper extends BaseMapper<AdminPermission> {

    /**
     * 查某子账号已分配的权限点编码集合（喂给 Sa-Token StpInterface）。
     */
    @Select("""
            SELECT p.code
            FROM admin_permission ap
            JOIN permission p ON ap.permission_id = p.id
            WHERE ap.admin_user_id = #{adminId}
              AND ap.is_deleted = 0
              AND p.is_deleted = 0
            """)
    List<String> selectCodesByAdminId(@Param("adminId") long adminId);

    /**
     * 持有开单权（{@code honor:reward-punish}）、却一个处置档位的权限点都没有的启用中子账号（「账号（部门）」）。
     *
     * <p>V85 之前开单权自带「限制参加活动」「限制发布社区」两档；V85 起三档各一个权限点，V86 / V92 按部门文字补授。
     * 部门文字对不上（写错、带空格、没填）的账号会<b>悄悄</b>失去原本能开的档位——开单弹窗里那几档直接消失，
     * 接口只回「缺权限」。启动时把这些账号列进日志（{@code SanctionPermissionAudit}），让它不再是悄悄的。</p>
     */
    @Select("""
            SELECT CONCAT(u.username, '（', COALESCE(NULLIF(TRIM(u.department), ''), '未填部门'), '）')
            FROM admin_user u
            WHERE u.is_deleted = 0 AND u.is_super_admin = 0 AND u.status = 0
              AND EXISTS (SELECT 1 FROM admin_permission ap JOIN permission p ON p.id = ap.permission_id
                          WHERE ap.admin_user_id = u.id AND ap.is_deleted = 0 AND p.is_deleted = 0
                            AND p.code = 'honor:reward-punish')
              AND NOT EXISTS (SELECT 1 FROM admin_permission ap JOIN permission p ON p.id = ap.permission_id
                              WHERE ap.admin_user_id = u.id AND ap.is_deleted = 0 AND p.is_deleted = 0
                                AND p.code IN ('honor:sanction-activity', 'honor:sanction-community',
                                               'honor:sanction-all'))
            ORDER BY u.id
            """)
    List<String> selectPunishersWithoutAnySanctionScope();

    /**
     * 物理删除某子账号的全部权限关联（全量替换/删账号时用）。
     *
     * <p>本表是纯关联表，全量替换语义；用物理删除而非逻辑删除——否则被逻辑删的旧行仍占用
     * 唯一键 {@code uk_admin_perm(admin_user_id, permission_id)}，再次分配同一权限会撞唯一键。</p>
     */
    @Delete("DELETE FROM admin_permission WHERE admin_user_id = #{adminId}")
    void physicalDeleteByAdminId(@Param("adminId") long adminId);
}
