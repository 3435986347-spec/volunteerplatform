package com.hengde.data;

import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.data.service.DashboardService;
import com.hengde.data.vo.PlatformSummaryVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据汇总（V4 数据汇总批，Row 79 九块）。
 *
 * <p><b>断言一律是增量</b>（先取一份基线、造数、再取一份，比差值）——汇总是全库聚合，
 * 同一个库里别的用例也在写数据，写「等于几」必然随机红。每条口径再配一个<b>不该算进去</b>的反例。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class PlatformSummaryTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 100_000L);

    @Autowired
    private DashboardService dashboardService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void summaryCountsEachThingOnce_andGivesNullForWhatTheSystemDoesNotTrack() {
        PlatformSummaryVO before = dashboardService.summary();

        long n = SEQ.incrementAndGet();
        // 一个游客（没实名）+ 一个已实名志愿者：用户人数两个都算，注册人数只算后者
        long guest = insertVolunteer("guest" + n, null, 0);
        long member = insertVolunteer("member" + n, LocalDateTime.now(), 0);
        long banned = insertVolunteer("banned" + n, LocalDateTime.now(), 1);
        // 注销是本人退出，不算「被封」——没有这一行的话「封号人数把注销也算进去」这种改动看不出来
        insertVolunteer("closed" + n, LocalDateTime.now(), 2);

        // 社区：一条正常帖 + 一条被驳回的 + 一条被后台隐藏的（同一个人发的两条违规帖只算一个人）
        insertPost(member, 1, 0, 0);
        insertPost(member, 1, 2, 0);
        insertPost(member, 1, 1, 1);
        // 命中关键词还在排队的不算违规（只是没审完）
        insertKeywordPendingPost(guest);

        // 小组：一个生效中的（状态 1）+ 一个已解散的（状态 3）
        insertGroup("小组A" + n, 1, null);
        insertGroup("小组B" + n, 3, LocalDateTime.now());

        // 举报：同一个人报两次（人数去重）+ 一条成立 + 一条不成立
        insertReport(member, 1);
        insertReport(member, 2);
        insertReport(guest, 0);

        PlatformSummaryVO after = dashboardService.summary();

        assertEquals(4, after.getPlatform().getUsers() - before.getPlatform().getUsers(), "用户人数含游客");
        assertEquals(3, after.getPlatform().getRegisteredVolunteers() - before.getPlatform().getRegisteredVolunteers(),
                "注册人数只算已实名的");
        assertEquals(1, after.getReport().getBannedAccounts() - before.getReport().getBannedAccounts(),
                "封号人数＝被禁用的账号，注销的不算");

        assertEquals(3, after.getReport().getReports() - before.getReport().getReports());
        assertEquals(1, after.getReport().getValid() - before.getReport().getValid());
        assertEquals(1, after.getReport().getInvalid() - before.getReport().getInvalid());
        assertEquals(2, after.getReport().getReporters() - before.getReport().getReporters(),
                "举报人数去重（同一个人报两次算一个人）");

        assertEquals(4, after.getCommunity().getPosts() - before.getCommunity().getPosts());
        assertEquals(2, after.getCommunity().getViolatingPosts() - before.getCommunity().getViolatingPosts(),
                "违规＝被驳回的 ∪ 被隐藏的；命中关键词还在排队的不算");
        assertEquals(1, after.getCommunity().getViolatingAuthors() - before.getCommunity().getViolatingAuthors(),
                "违规人次按人去重（同一个人的两条违规帖算一个人）");

        assertEquals(2, after.getGroup().getGroups() - before.getGroup().getGroups());
        assertEquals(1, after.getGroup().getActiveGroups() - before.getGroup().getActiveGroups());
        assertEquals(1, after.getGroup().getDissolvedGroups() - before.getGroup().getDissolvedGroups(),
                "Row 79 的「被封小组数」在系统里对应的是已解散");

        // 系统里没有的概念：给 null 不给 0（0 会被读成「一次都没有」）
        assertNull(after.getAlbum().getVideos(), "相册只收照片，没有视频这个概念");
        assertNull(after.getAlbum().getViews(), "查看人次没有埋点");
        assertNull(after.getAlbum().getDownloaders());
        assertNull(after.getAlbum().getDownloads());
        assertNull(after.getDonation().getBook().getBookHouses(), "「修建书屋数」系统里没有（V3 收尾批已定）");

        // 三块捐赠数据与专门那个接口是同一份
        assertEquals(dashboardService.donationSummary().getWish().getPublished(),
                after.getDonation().getWish().getPublished(), "捐赠三块复用同一口径，不另写一份");
        assertTrue(after.getPlatform().getTotalServiceHours() >= 0);
    }

    // ================= 造数（直接写库：这条用例验的是汇总口径，不是各域的写入流程）=================

    private long insertVolunteer(String key, LocalDateTime registerTime, int status) {
        jdbc.update("INSERT INTO volunteer (openid, real_name, nick_name, status, manager_flag, register_time, "
                        + "create_time, update_time, is_deleted) VALUES (?, '汇总用例', ?, ?, 0, ?, NOW(), NOW(), 0)",
                "test:summary:" + key + ":" + System.nanoTime(), "昵称" + key, status, registerTime);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertPost(long authorId, int authorType, int reviewStatus, int adminHidden) {
        jdbc.update("INSERT INTO social_post (author_type, author_id, content, media_type, visibility, allow_comment, "
                        + "allow_like, review_status, review_level, keyword_hit, admin_hidden, pinned, view_count, "
                        + "like_count, comment_count, share_count, create_time, update_time, is_deleted) "
                        + "VALUES (?, ?, '汇总用例帖子', 0, 0, 1, 1, ?, 0, 0, ?, 0, 0, 0, 0, 0, NOW(), NOW(), 0)",
                authorType, authorId, reviewStatus, adminHidden);
    }

    private void insertKeywordPendingPost(long authorId) {
        jdbc.update("INSERT INTO social_post (author_type, author_id, content, media_type, visibility, allow_comment, "
                + "allow_like, review_status, review_level, keyword_hit, admin_hidden, pinned, view_count, "
                + "like_count, comment_count, share_count, create_time, update_time, is_deleted) "
                + "VALUES (1, ?, '命中关键词还在排队', 0, 0, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, NOW(), NOW(), 0)", authorId);
    }

    private void insertReport(long reporterId, int status) {
        jdbc.update("INSERT INTO social_report (reporter_id, target_type, target_id, post_id, reason, status, create_time) "
                + "VALUES (?, 1, ?, ?, '汇总用例举报', ?, NOW())", reporterId, SEQ.incrementAndGet(), SEQ.get(), status);
    }

    private void insertGroup(String name, int status, LocalDateTime dissolveTime) {
        jdbc.update("INSERT INTO volunteer_group (group_no, name, leader_id, status, dissolve_time, create_time, "
                        + "update_time, is_deleted) VALUES (?, ?, 1, ?, ?, NOW(), NOW(), 0)",
                "G" + SEQ.incrementAndGet() + System.nanoTime() % 10000, name, status, dissolveTime);
    }
}
