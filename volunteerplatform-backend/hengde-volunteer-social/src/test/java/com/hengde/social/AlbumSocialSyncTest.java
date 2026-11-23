package com.hengde.social;

import com.hengde.activity.album.dto.AlbumDTOs;
import com.hengde.activity.album.service.AlbumService;
import com.hengde.activity.dto.ActivityCreateDTO;
import com.hengde.activity.dto.ActivitySlotDTO;
import com.hengde.activity.service.ActivityService;
import com.hengde.activity.service.EnrollmentService;
import com.hengde.auth.constant.SanctionScope;
import com.hengde.auth.entity.VolunteerSanction;
import com.hengde.auth.service.SanctionService;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.social.dao.SocialPostMapper;
import com.hengde.social.entity.SocialPost;
import com.hengde.social.service.SocialKeywordService;
import com.hengde.social.service.SocialMediaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 相册上传「默认勾选发送到交流平台」（V4 活动相册批，Row 30）：activity 发事件、social 监听发帖——走真实入口 {@link AlbumService#upload}，证明事件链路真的接上了。
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class AlbumSocialSyncTest extends SocialTestSupport {

    private static final long ADMIN = 12L;

    @Autowired
    private AlbumService albumService;
    @Autowired
    private ActivityService activityService;
    @Autowired
    private EnrollmentService enrollmentService;
    @Autowired
    private SanctionService sanctionService;
    @Autowired
    private SocialKeywordService keywordService;
    @Autowired
    private SocialMediaService mediaService;
    @Autowired
    private SocialPostMapper postMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void uploadWithSync_postsToCommunity_skipsWhenOffOrBanned() {
        Long aid = activityService.publish(activity(40), ADMIN);
        Long albumId = albumService.albumOfActivity(aid).getId();
        String albumTitle = albumService.detail(albumId, null).getTitle();

        Long me = enrolled(aid);
        AlbumDTOs.Upload up = photos(12);
        up.setComment("今天的活动很开心");
        Long batch = albumService.upload(me, albumId, up);
        Long postId = jdbc.queryForObject("SELECT social_post_id FROM activity_album_batch WHERE id = ?", Long.class, batch);
        assertNotNull(postId, "同步出去的帖子 id 回写到批次上");
        SocialPost post = postMapper.selectById(postId);
        assertEquals(me, post.getAuthorId());
        assertEquals("【" + albumTitle + "】今天的活动很开心", post.getContent());
        assertEquals(up.getPhotoUrls().subList(0, 9), mediaService.fromJson(post.getMediaUrls()), "一条帖子最多带前 9 张");

        Long quiet = enrolled(aid);
        AlbumDTOs.Upload noSync = photos(2);
        noSync.setSyncSocial(false);
        Long quietBatch = albumService.upload(quiet, albumId, noSync);
        assertNull(jdbc.queryForObject("SELECT social_post_id FROM activity_album_batch WHERE id = ?", Long.class, quietBatch));
        assertEquals(0, posts(quiet));

        Long banned = enrolled(aid);
        sanctionService.impose(banned, VolunteerSanction.SOURCE_REWARD_PUNISH, SEQ.incrementAndGet() + 7_000_000L, SanctionScope.COMMUNITY_POST, 3);
        Long bannedBatch = albumService.upload(banned, albumId, photos(3));
        assertNotNull(bannedBatch, "被禁止发帖不影响往相册传");
        assertEquals(0, posts(banned), "被禁止发帖的人不同步");

        String word = "相册敏感" + SEQ.incrementAndGet();
        keywordService.add(admin("宣传部"), word);
        Long hitter = enrolled(aid);
        AlbumDTOs.Upload hit = photos(1);
        hit.setComment("评论里有" + word);
        Long hitBatch = albumService.upload(hitter, albumId, hit);
        Long hitPost = jdbc.queryForObject("SELECT social_post_id FROM activity_album_batch WHERE id = ?", Long.class, hitBatch);
        assertEquals(1, postMapper.selectById(hitPost).getKeywordHit(), "同步的帖子一样过关键词风控");
        assertTrue(post.getReviewStatus() == 0, "同步的帖子一样先发后审");
    }

    private long posts(Long author) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM social_post WHERE author_type = 1 AND author_id = ?", Long.class, author);
    }

    private Long enrolled(Long aid) {
        Long v = member();
        enrollmentService.enroll(aid, List.of(jdbc.queryForObject("SELECT id FROM activity_slot WHERE activity_id = ? LIMIT 1", Long.class, aid)), v);
        return v;
    }

    private static AlbumDTOs.Upload photos(int n) {
        AlbumDTOs.Upload u = new AlbumDTOs.Upload();
        u.setPhotoUrls(IntStream.range(0, n).mapToObj(i -> "[oss-disabled]/album/" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "/" + UUID.randomUUID().toString().replace("-", "") + ".jpg").toList());
        return u;
    }

    private static ActivityCreateDTO activity(int daysAhead) {
        LocalDateTime start = LocalDateTime.now().plusDays(daysAhead).withHour(9).withMinute(0).withSecond(0).withNano(0);
        ActivitySlotDTO s = new ActivitySlotDTO();
        s.setProjectName("相册同步");
        s.setStartTime(start);
        s.setEndTime(start.plusHours(2));
        s.setNeedCount(0);
        ActivityCreateDTO t = new ActivityCreateDTO();
        t.setTitle("相册同步活动_" + SEQ.incrementAndGet());
        t.setStartTime(start);
        t.setEndTime(start.plusHours(2));
        t.setSlots(List.of(s));
        return t;
    }
}
