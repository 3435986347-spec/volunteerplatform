package com.hengde.system.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.system.entity.SysFileShare;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 分享链接。
 *
 * @author hengde
 */
@Mapper
public interface SysFileShareMapper extends BaseMapper<SysFileShare> {

    @Select("SELECT * FROM sys_file_share WHERE token = #{token} AND is_deleted = 0")
    SysFileShare selectByToken(@Param("token") String token);

    @Select("SELECT * FROM sys_file_share WHERE file_id = #{fileId} AND is_deleted = 0 ORDER BY id DESC")
    List<SysFileShare> selectByFile(@Param("fileId") Long fileId);

    /**
     * 记一次下载：<b>用影响行数当最后一道闸</b>（条件里带着「没撤销」），
     * 与电子证书签发前那一下同一形状——撤销与打开之间总有几百毫秒。
     */
    @Update("UPDATE sys_file_share SET download_count = download_count + 1 WHERE id = #{id} AND is_deleted = 0")
    int countDownload(@Param("id") Long id);

    @Update("UPDATE sys_file_share SET is_deleted = 1 WHERE id = #{id} AND is_deleted = 0")
    int revoke(@Param("id") Long id);
}
