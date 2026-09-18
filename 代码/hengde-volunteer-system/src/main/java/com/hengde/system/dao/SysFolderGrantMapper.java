package com.hengde.system.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.system.entity.SysFolderGrant;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文件夹授权。
 *
 * @author hengde
 */
@Mapper
public interface SysFolderGrantMapper extends BaseMapper<SysFolderGrant> {

    @Select("SELECT * FROM sys_folder_grant WHERE folder_id = #{folderId} ORDER BY id ASC")
    List<SysFolderGrant> selectByFolder(@Param("folderId") Long folderId);

    /** 这个人（或他的部门）在这个文件夹上有没有授权、能不能写。 */
    @Select("<script>SELECT * FROM sys_folder_grant WHERE folder_id = #{folderId} "
            + "AND ((grantee_type = 1 AND admin_id = #{adminId})"
            + "<if test='department != null'> OR (grantee_type = 2 AND department = #{department})</if>)</script>")
    List<SysFolderGrant> selectForAdmin(@Param("folderId") Long folderId, @Param("adminId") Long adminId,
                                        @Param("department") String department);

    @Delete("DELETE FROM sys_folder_grant WHERE id = #{id}")
    int deleteRow(@Param("id") Long id);
}
