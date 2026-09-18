package com.hengde.system.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.system.entity.SysFolder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 网盘文件夹。
 *
 * @author hengde
 */
@Mapper
public interface SysFolderMapper extends BaseMapper<SysFolder> {

    @Select("<script>SELECT * FROM sys_folder WHERE is_deleted = 0"
            + "<if test='parentId == null'> AND parent_id IS NULL</if>"
            + "<if test='parentId != null'> AND parent_id = #{parentId}</if>"
            + " ORDER BY sort ASC, id ASC</script>")
    List<SysFolder> selectChildren(@Param("parentId") Long parentId);

    @Select("SELECT COUNT(*) FROM sys_folder WHERE parent_id = #{parentId} AND is_deleted = 0")
    long countChildren(@Param("parentId") Long parentId);

    @Select("SELECT * FROM sys_folder WHERE is_deleted = 0 ORDER BY sort ASC, id ASC")
    List<SysFolder> selectAll();
}
