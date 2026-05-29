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
     * 物理删除某子账号的全部权限关联（全量替换/删账号时用）。
     *
     * <p>本表是纯关联表，全量替换语义；用物理删除而非逻辑删除——否则被逻辑删的旧行仍占用
     * 唯一键 {@code uk_admin_perm(admin_user_id, permission_id)}，再次分配同一权限会撞唯一键。</p>
     */
    @Delete("DELETE FROM admin_permission WHERE admin_user_id = #{adminId}")
    void physicalDeleteByAdminId(@Param("adminId") long adminId);
}
