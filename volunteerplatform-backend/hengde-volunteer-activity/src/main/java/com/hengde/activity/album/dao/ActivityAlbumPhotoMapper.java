package com.hengde.activity.album.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.activity.album.entity.ActivityAlbumPhoto;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 相册照片（V74）。 */
public interface ActivityAlbumPhotoMapper extends BaseMapper<ActivityAlbumPhoto> {

    @Update("UPDATE activity_album_photo SET is_deleted = 1, deleted_by = #{adminId}, update_time = NOW() WHERE id = #{id} AND is_deleted = 0")
    int softDelete(@Param("id") Long id, @Param("adminId") Long adminId);

    /** 一批相册的张数、最新一张的 id、最后上传时间（列表页一次查完）。 */
    @Select("<script>SELECT album_id AS albumId, COUNT(*) AS cnt, MAX(id) AS lastId, MAX(create_time) AS lastTime "
            + "FROM activity_album_photo WHERE is_deleted = 0 AND album_id IN "
            + "<foreach collection='albumIds' item='i' open='(' separator=',' close=')'>#{i}</foreach> GROUP BY album_id</script>")
    List<Map<String, Object>> selectStats(@Param("albumIds") Collection<Long> albumIds);

    /** 一批上传批次各自还剩几张没删。 */
    @Select("<script>SELECT batch_id AS batchId, COUNT(*) AS cnt FROM activity_album_photo WHERE is_deleted = 0 AND batch_id IN "
            + "<foreach collection='batchIds' item='i' open='(' separator=',' close=')'>#{i}</foreach> GROUP BY batch_id</script>")
    List<Map<String, Object>> selectBatchRemaining(@Param("batchIds") Collection<Long> batchIds);
}
