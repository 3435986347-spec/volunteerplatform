package com.hengde.activity.album.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.activity.album.entity.ActivityAlbum;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 活动相册（V74）。 */
public interface ActivityAlbumMapper extends BaseMapper<ActivityAlbum> {

    @Update("UPDATE activity_album SET is_deleted = 1, deleted_by = #{adminId}, update_time = NOW() WHERE id = #{id} AND is_deleted = 0")
    int softDelete(@Param("id") Long id, @Param("adminId") Long adminId);
}
