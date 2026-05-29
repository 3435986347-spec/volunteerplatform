package com.hengde.auth.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.auth.entity.AdminUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 后台账号 Mapper。继承 {@link BaseMapper} 即得基础 CRUD。
 *
 * @author hengde
 */
@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {
}
