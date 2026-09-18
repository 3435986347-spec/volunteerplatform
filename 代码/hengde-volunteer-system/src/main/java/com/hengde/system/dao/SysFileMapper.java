package com.hengde.system.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.system.entity.SysFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 网盘文件。
 *
 * @author hengde
 */
@Mapper
public interface SysFileMapper extends BaseMapper<SysFile> {

    @Select("<script>SELECT * FROM sys_file WHERE folder_id = #{folderId} AND is_deleted = 0"
            + "<if test='keyword != null'> AND (name LIKE CONCAT('%', #{keyword}, '%') OR serial_no LIKE CONCAT('%', #{keyword}, '%'))</if>"
            + " ORDER BY id DESC LIMIT #{offset}, #{limit}</script>")
    List<SysFile> selectInFolder(@Param("folderId") Long folderId, @Param("keyword") String keyword,
                                 @Param("offset") long offset, @Param("limit") long limit);

    @Select("<script>SELECT COUNT(*) FROM sys_file WHERE folder_id = #{folderId} AND is_deleted = 0"
            + "<if test='keyword != null'> AND (name LIKE CONCAT('%', #{keyword}, '%') OR serial_no LIKE CONCAT('%', #{keyword}, '%'))</if></script>")
    long countInFolder(@Param("folderId") Long folderId, @Param("keyword") String keyword);

    @Select("SELECT COUNT(*) FROM sys_file WHERE folder_id = #{folderId} AND is_deleted = 0")
    long countByFolder(@Param("folderId") Long folderId);

    /**
     * 现在对志愿者开放的文件（Row 19 的「文件开放时间」）。
     *
     * <p><b>窗口按传进来的「现在」现算</b>，不靠定时任务改状态位：漏跑一次，本该开放的文件就一直关着，
     * 而且没有人会发现——这条纪律本项目在名单公示与处置到期上已经用过两次。</p>
     */
    @Select("SELECT * FROM sys_file WHERE is_deleted = 0 AND published = 1 "
            + "AND (publish_start IS NULL OR publish_start <= #{now}) "
            + "AND (publish_end IS NULL OR publish_end > #{now}) "
            + "ORDER BY id DESC")
    List<SysFile> selectOpenNow(@Param("now") LocalDateTime now);

    /** 软删走显式 SQL（{@code @TableLogic} 列不能经 wrapper set）。 */
    @Update("UPDATE sys_file SET is_deleted = 1, update_time = NOW() WHERE id = #{id} AND is_deleted = 0")
    int softDelete(@Param("id") Long id);
}
