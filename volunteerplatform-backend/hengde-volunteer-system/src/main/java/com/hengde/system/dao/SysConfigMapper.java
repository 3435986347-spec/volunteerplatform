package com.hengde.system.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.system.entity.SysConfig;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 后台配置（整份覆盖，没有并发窗口）。
 *
 * @author hengde
 */
@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig> {

    @Insert("INSERT INTO sys_config (config_key, config_value, updated_by, update_time) "
            + "VALUES (#{key}, #{value}, #{adminId}, NOW()) "
            + "ON DUPLICATE KEY UPDATE config_value = VALUES(config_value), updated_by = VALUES(updated_by), update_time = NOW()")
    int upsert(@Param("key") String key, @Param("value") String value, @Param("adminId") Long adminId);
}
