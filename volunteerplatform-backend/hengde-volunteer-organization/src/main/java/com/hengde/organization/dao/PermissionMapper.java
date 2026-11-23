package com.hengde.organization.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.organization.entity.Permission;
import org.apache.ibatis.annotations.Mapper;

/**
 * 权限点 Mapper。
 *
 * @author hengde
 */
@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {
}
