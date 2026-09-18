package com.hengde.activity;

import com.hengde.activity.album.dao.ActivityAlbumMapper;
import com.hengde.activity.album.dto.AlbumDTOs;
import com.hengde.activity.album.entity.ActivityAlbum;
import com.hengde.activity.album.service.AlbumService;
import com.hengde.activity.album.vo.AlbumVOs;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.dto.ActivityCreateDTO;
import com.hengde.activity.dto.ActivitySlotDTO;
import com.hengde.activity.event.AlbumPhotosUploadedEvent;
import com.hengde.activity.service.ActivityLeaderService;
import com.hengde.activity.service.ActivityService;
import com.hengde.activity.service.EnrollmentService;
import com.hengde.activity.service.PointService;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.constant.Gender;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动相册（V4 活动相册批，V74）：自动建册、上传权限、上传校验、上传记录与统计、积分审核（按剩余张数 / 规则 / 每人每相册上限）、同步社区事件。
 *
 * <p>规则是全库一行：会改它的用例在结束前改回默认（每 3 张 1 分、上限 10）。<b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@RecordApplicationEvents
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class AlbumServiceTest extends AlbumTestSupport {

    @Autowired
    ApplicationEvents events;

    @Test
    void autoCreate_titleIdempotent_andOnlyForLiveActivities() {
        Long aid = activityService.publish(base(), ADMIN);
        ActivityAlbum album = albumService.albumOfActivity(aid);
        Long serial = jdbc.queryForObject("SELECT serial_no FROM activity WHERE id = ?", Long.class, aid);
        String title = jdbc.queryForObject("SELECT title FROM activity WHERE id = ?", String.class, aid);
        assertEquals(serial + " " + title, album.getTitle());
        assertEquals(album.getId(), albumService.albumOfActivity(aid).getId(), "再打开还是那一个");
        assertEquals(album.getId(), albumService.create(ADMIN, create(aid, null)), "后台按活动新增也是那一个");

        Long cancelled = activityService.publish(base(), ADMIN);
        activityService.cancel(cancelled, "取消");
        assertMessage("还没发布", () -> albumService.albumOfActivity(cancelled));
        assertMessage("标题", () -> albumService.create(ADMIN, create(null, " ")));
        Long custom = albumService.create(ADMIN, create(null, "年度合影-" + SEQ.incrementAndGet()));
        assertNull(albumMapper.selectById(custom).getActivityId());

        albumService.deleteAlbum(ADMIN, album.getId());
        assertMessage("相册不存在", () -> albumService.detail(album.getId(), null));
        assertTrue(albumService.albumOfActivity(aid).getId() > album.getId(), "删了之后再打开会重新建一个");
    }

    @Test
    void uploadPermissions_enrolledLeaderManager() {
        Long aid = activityService.publish(base(), ADMIN);
        Long slot = firstSlot(aid);
        ActivityAlbum album = albumService.albumOfActivity(aid);
        Long enrolled = volunteer(true, 0);
        enrollmentService.enroll(aid, List.of(slot), enrolled);
        Long leader = volunteer(true, 0);
        enrollmentService.enroll(aid, List.of(slot), leader);
        leaderService.assign(aid, 1, leader, ADMIN);
        jdbc.update("DELETE FROM activity_enrollment WHERE activity_id = ? AND volunteer_id = ?", aid, leader);
        Long manager = volunteer(true, 1);
        Long outsider = volunteer(true, 0);
        Long guest = volunteer(false, 0);

        assertTrue(albumService.canUpload(enrolled, album), "报名已通过的志愿者");
        ActivityCreateDTO audited = base(4);
        audited.setNeedAudit(1);
        Long auditedAid = activityService.publish(audited, ADMIN);
        Long pending = volunteer(true, 0);
        enrollmentService.enroll(auditedAid, List.of(firstSlot(auditedAid)), pending);
        assertFalse(albumService.canUpload(pending, albumService.albumOfActivity(auditedAid)), "报名还在待审核的不算");
        assertTrue(albumService.canUpload(leader, album), "活动负责人");
        assertTrue(albumService.canUpload(manager, album), "管理团队");
        assertFalse(albumService.canUpload(outsider, album));
        assertFalse(albumService.canUpload(guest, album));
        ActivityAlbum custom = albumMapper.selectById(albumService.create(ADMIN, create(null, "不挂活动-" + SEQ.incrementAndGet())));
        assertTrue(albumService.canUpload(manager, custom), "管理团队可以往任何相册传");
        assertFalse(albumService.canUpload(enrolled, custom));
        assertMessage("管理团队能往这个相册传", () -> albumService.upload(outsider, album.getId(), upload(1)));
        assertTrue(albumService.detail(album.getId(), enrolled).getUploadable());
        assertFalse(albumService.detail(album.getId(), outsider).getUploadable());
    }

    @Test
    void uploadValidation_recordsStats_andSyncEvent() {
        Long aid = activityService.publish(base(), ADMIN);
        Long me = enrolledVolunteer(aid);
        Long albumId = albumService.albumOfActivity(aid).getId();
        assertMessage("至少选一张", () -> albumService.upload(me, albumId, upload(0)));
        assertMessage("最多传 50 张", () -> albumService.upload(me, albumId, upload(51)));
        AlbumDTOs.Upload foreign = upload(1);
        foreign.setPhotoUrls(List.of("https://evil.example.com/a.jpg"));
        assertMessage("先通过小程序上传", () -> albumService.upload(me, albumId, foreign));

        AlbumDTOs.Upload first = upload(12);
        first.setComment("  现场照片  ");
        events.clear();
        Long b1 = albumService.upload(me, albumId, first);
        List<AlbumPhotosUploadedEvent> published = events.stream(AlbumPhotosUploadedEvent.class).toList();
        assertEquals(1, published.size(), "默认同步社区");
        assertEquals(b1, published.get(0).batchId());
        assertEquals("现场照片", published.get(0).comment());
        AlbumDTOs.Upload second = upload(2);
        second.setSyncSocial(false);
        events.clear();
        Long b2 = albumService.upload(me, albumId, second);
        assertEquals(0, events.stream(AlbumPhotosUploadedEvent.class).count(), "不勾就不同步");

        AlbumVOs.Album vo = albumService.detail(albumId, me);
        assertEquals(14, vo.getPhotoCount());
        assertEquals(second.getPhotoUrls().get(1), vo.getCoverUrl(), "封面是最新一张");
        List<AlbumVOs.Batch> records = albumService.batches(albumId, page(10)).getRecords();
        assertEquals(List.of(b2, b1), records.stream().map(AlbumVOs.Batch::getId).toList());
        assertEquals(9, records.get(1).getPreviewUrls().size(), "预览最多 9 张");
        assertTrue(records.get(1).getUploaderName().startsWith("相册志愿者"));

        Long photoId = jdbc.queryForObject("SELECT id FROM activity_album_photo WHERE batch_id = ? ORDER BY id LIMIT 1", Long.class, b1);
        albumService.deletePhoto(ADMIN, photoId);
        assertMessage("照片不存在", () -> albumService.deletePhoto(ADMIN, photoId));
        assertEquals(11, albumService.batches(albumId, page(10)).getRecords().get(1).getRemainingCount());
        assertEquals(13, albumService.downloadList(albumId).size());
        assertTrue(albumService.downloadList(albumId).get(0).get("filename").endsWith("_1.jpg"));

        Long adminBatch = albumService.adminUpload(ADMIN, albumId, upload(3));
        assertEquals(AlbumService.POINTS_NONE, jdbc.queryForObject("SELECT points_status FROM activity_album_batch WHERE id = ?", Integer.class, adminBatch));
    }

    @Test
    void points_byRemainingPhotos_cappedPerPersonPerAlbum_andRuleSwitch() {
        Long aid = activityService.publish(base(), ADMIN);
        Long me = enrolledVolunteer(aid);
        Long albumId = albumService.albumOfActivity(aid).getId();
        try {
            Long b1 = albumService.upload(me, albumId, noSync(6));
            Long photoId = jdbc.queryForObject("SELECT id FROM activity_album_photo WHERE batch_id = ? LIMIT 1", Long.class, b1);
            albumService.deletePhoto(ADMIN, photoId);   // 6 → 5 张，审核时按 5 张算：1 分（按上传时的 6 张会是 2 分）
            assertEquals(1, albumService.approvePoints(ADMIN, b1));
            assertMessage("不在待审核", () -> albumService.approvePoints(ADMIN, b1));

            Long b2 = albumService.upload(me, albumId, noSync(30));   // 30 张＝10 分，但上限只剩 9
            assertEquals(9, albumService.approvePoints(ADMIN, b2));
            Long b3 = albumService.upload(me, albumId, noSync(9));
            assertEquals(0, albumService.approvePoints(ADMIN, b3), "上限用完了");
            assertEquals(10, pointService.summary(me).getTotalEarned());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM point_record WHERE volunteer_id = ? AND source_type = ?",
                    Integer.class, me, PointSourceType.ALBUM), "0 分不入账");
            assertEquals("相册上传", PointSourceType.labelOf(PointSourceType.ALBUM));

            Long b4 = albumService.upload(me, albumId, noSync(3));
            assertMessage("驳回原因", () -> albumService.rejectPoints(ADMIN, b4, " "));
            albumService.rejectPoints(ADMIN, b4, "照片与活动无关");
            assertMessage("不在待审核", () -> albumService.approvePoints(ADMIN, b4));

            Long otherAid = activityService.publish(base(5), ADMIN);
            enrollmentService.enroll(otherAid, List.of(firstSlot(otherAid)), me);
            Long otherAlbum = albumService.albumOfActivity(otherAid).getId();
            assertEquals(1, albumService.approvePoints(ADMIN, albumService.upload(me, otherAlbum, noSync(3))), "上限按相册算");

            assertTrue(albumService.pointsQueue(0, page(500)).getRecords().stream().noneMatch(b -> b.getId().equals(b1)));

            albumService.saveRule(ADMIN, rule(false, 3, 1, 10));
            Long off = albumService.upload(me, otherAlbum, noSync(3));
            assertEquals(AlbumService.POINTS_NONE, jdbc.queryForObject("SELECT points_status FROM activity_album_batch WHERE id = ?", Integer.class, off));
            assertMessage("1~100", () -> albumService.saveRule(ADMIN, rule(true, 0, 1, 10)));
        } finally {
            albumService.saveRule(ADMIN, rule(true, 3, 1, 10));
        }
    }
}
