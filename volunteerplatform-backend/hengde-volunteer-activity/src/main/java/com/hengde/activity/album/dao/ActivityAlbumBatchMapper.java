package com.hengde.activity.album.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.activity.album.entity.ActivityAlbumBatch;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 相册上传批次（V74）。 */
public interface ActivityAlbumBatchMapper extends BaseMapper<ActivityAlbumBatch> {

    /**
     * 「这个人在这个相册」已经拿到的分（审核通过前复核上限用）。<b>普通读</b>：同一个人同一相册的审核由
     * {@code AlbumService} 按 {@code lock:album-points:相册:人} 串行，写 {@code awarded_points} 的只有审核通过这一处，
     * 事务在拿到那把锁之后才开始，读视图看得到上一个审核已提交的分。
     *
     * <p>⚠️ 不要改回 {@code FOR UPDATE}：那是一条范围当前读，压测里（{@code AlbumConcurrencyTest} 的 stress 用例）
     * 优化器把它走成了主键扫描、一次锁上百行，与上传的 INSERT、另一个人的审核成环死锁（2026-10-17 InnoDB 死锁报告实证）。</p>
     */
    @Select("SELECT COALESCE(SUM(awarded_points), 0) FROM activity_album_batch "
            + "WHERE album_id = #{albumId} AND uploader_type = 1 AND uploader_id = #{volunteerId}")
    int sumAwarded(@Param("albumId") Long albumId, @Param("volunteerId") Long volunteerId);

    /** 按主键当前读锁住这一批（行一定存在，只上行锁、不上间隙锁）；读到的审核状态是最新的（驳回不走那把分布式锁）。批次表没有逻辑删除列。 */
    @Select("SELECT * FROM activity_album_batch WHERE id = #{id} FOR UPDATE")
    ActivityAlbumBatch selectByIdForUpdate(@Param("id") Long id);
}
