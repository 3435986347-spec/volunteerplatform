package com.hengde.activity.album.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.activity.album.entity.ActivityAlbumBatch;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 相册上传批次（V74）。 */
public interface ActivityAlbumBatchMapper extends BaseMapper<ActivityAlbumBatch> {

    /**
     * 当前读锁住「这个人在这个相册」已经拿到的分（审核通过前复核上限用）。锁的是这个人在这个相册的全部批次行，
     * 同一个人的两批同时审核在这里排队——否则两批各自读到「还差 5 分到上限」、各发 5 分，就越过了上限。
     */
    @Select("SELECT COALESCE(SUM(awarded_points), 0) FROM activity_album_batch "
            + "WHERE album_id = #{albumId} AND uploader_type = 1 AND uploader_id = #{volunteerId} FOR UPDATE")
    int sumAwardedForUpdate(@Param("albumId") Long albumId, @Param("volunteerId") Long volunteerId);
}
