package com.hengde.organization.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.organization.entity.VolunteerPermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 志愿者-权限点关联 Mapper（镜像 {@link AdminPermissionMapper}，志愿者域）。
 *
 * @author hengde
 */
@Mapper
public interface VolunteerPermissionMapper extends BaseMapper<VolunteerPermission> {

    /**
     * 查某志愿者已分配的权限点编码集合（喂给 Sa-Token StpInterface）。
     *
     * <p>额外加 {@code p.volunteer_grantable = 1} 过滤——即便历史脏行授了非白名单权限，
     * 也不会经志愿者 token 外泄，杜绝越权。</p>
     */
    @Select("""
            SELECT p.code
            FROM volunteer_permission vp
            JOIN permission p ON vp.permission_id = p.id
            WHERE vp.volunteer_id = #{volunteerId}
              AND vp.is_deleted = 0
              AND p.is_deleted = 0
              AND p.volunteer_grantable = 1
            """)
    List<String> selectCodesByVolunteerId(@Param("volunteerId") long volunteerId);

    /**
     * 物理删除某志愿者的全部权限关联（全量替换/删志愿者时用）。
     *
     * <p>理由同 admin_permission：纯关联表、全量替换语义；物理删而非逻辑删，否则被逻辑删的旧行仍占用
     * 唯一键 {@code uk_vol_perm(volunteer_id, permission_id)}，再次分配同一权限会撞唯一键。</p>
     */
    @Delete("DELETE FROM volunteer_permission WHERE volunteer_id = #{volunteerId}")
    void physicalDeleteByVolunteerId(@Param("volunteerId") long volunteerId);
}
