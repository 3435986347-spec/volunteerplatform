package com.hengde.activity.album.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 活动相册的数据汇总口径（V4 数据汇总批，Row 79「相册数据（相册总数、照片张数、视频总数、查看人次、上传人数、上传人次、下载人数、下载人次）」）。
 *
 * <p>⚠️ <b>其中四项系统里没有对应的东西</b>：相册只收照片（没有视频）；查看与下载都没有埋点。
 * 这四项在出参里<b>给 null 而不是 0</b>——0 会被读成「一次都没有」，而真相是「这个系统根本不记这件事」
 * （与捐书的「修建书屋数」同一处理，V3 收尾批已有先例）。</p>
 *
 * @author hengde
 */
@Service
public class AlbumStatsService {

    private JdbcTemplate jdbc;

    @Autowired
    public void setJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 相册总数（没删的）。 */
    public long countAlbums() {
        return one("SELECT COUNT(*) FROM activity_album WHERE is_deleted = 0");
    }

    /** 照片张数（没删的）。 */
    public long countPhotos() {
        return one("SELECT COUNT(*) FROM activity_album_photo WHERE is_deleted = 0");
    }

    /** 上传人数（志愿者去重；后台账号上传的不算「人」）。 */
    public long countUploaders() {
        return one("SELECT COUNT(DISTINCT uploader_id) FROM activity_album_batch WHERE uploader_type = 1");
    }

    /** 上传人次（每次上传一批算一次；批次表只追加，没有逻辑删除列）。 */
    public long countUploadBatches() {
        return one("SELECT COUNT(*) FROM activity_album_batch");
    }

    private long one(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }
}
