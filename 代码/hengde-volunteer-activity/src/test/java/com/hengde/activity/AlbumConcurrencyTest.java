package com.hengde.activity;

import com.hengde.activity.album.service.AlbumService;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动相册的并发（前三条<b>必跑</b>）与压测（{@code @Tag("stress")}）。
 *
 * <p>积分上限是「先读这个人在这个相册拿了多少、再发」——两批同时审核会各自以为还差几分，所以复核必须在当前读锁住这个人在这个相册的批次行之后做。
 * 靠赛跑证明不了这把锁，第一条用例由裸 JDBC 事务先锁住那些行，断言审核在等它。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class AlbumConcurrencyTest extends AlbumTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    private DataSource dataSource;

    @Test
    void approvalWaitsForTheUploadersBatchRows() throws Exception {
        Long aid = activityService.publish(base(7), ADMIN);
        Long me = enrolledVolunteer(aid);
        Long albumId = albumService.albumOfActivity(aid).getId();
        Long batch = albumService.upload(me, albumId, noSync(3));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM activity_album_batch WHERE album_id = ? AND uploader_type = 1 AND uploader_id = ? FOR UPDATE")) {
                ps.setLong(1, albumId);
                ps.setLong(2, me);
                ps.executeQuery();
            }
            Future<Integer> f = pool.submit(() -> albumService.approvePoints(ADMIN, batch));
            assertThrows(TimeoutException.class, () -> f.get(1500, TimeUnit.MILLISECONDS), "审核应当在等这个人在这个相册的批次行");
            conn.commit();
            assertEquals(1, f.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void fiveBatchesApprovedAtOnce_neverExceedTheCap() throws Exception {
        for (int round = 0; round < 5; round++) {
            Long aid = activityService.publish(base(10 + round), ADMIN);
            Long me = enrolledVolunteer(aid);
            Long albumId = albumService.albumOfActivity(aid).getId();
            List<Long> batches = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                batches.add(albumService.upload(me, albumId, noSync(9)));   // 每批 3 分，五批 15 分，上限 10
            }
            CyclicBarrier barrier = new CyclicBarrier(batches.size());
            ExecutorService pool = Executors.newFixedThreadPool(batches.size());
            List<Future<Integer>> futures = new ArrayList<>();
            for (Long b : batches) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return albumService.approvePoints(ADMIN, b);
                }));
            }
            int total = 0;
            for (Future<Integer> f : futures) {
                total += f.get(60, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertEquals(10, total, "五批同时审核加起来正好顶到上限");
            assertEquals(10, jdbc.queryForObject("SELECT COALESCE(SUM(change_amount), 0) FROM point_record WHERE volunteer_id = ? AND source_type = ?",
                    Integer.class, me, PointSourceType.ALBUM));
        }
    }

    @Test
    void sameBatchApprovedByFourReviewers_oneWins() throws Exception {
        Long aid = activityService.publish(base(25), ADMIN);
        Long me = enrolledVolunteer(aid);
        Long batch = albumService.upload(me, albumService.albumOfActivity(aid).getId(), noSync(6));
        CyclicBarrier barrier = new CyclicBarrier(4);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return albumService.approvePoints(ADMIN, batch);
            }));
        }
        int ok = 0;
        for (Future<Integer> f : futures) {
            try {
                f.get(60, TimeUnit.SECONDS);
                ok++;
            } catch (ExecutionException e) {
                assertInstanceOf(BusinessException.class, e.getCause(), "只允许业务拒绝：" + e.getCause());
            }
        }
        pool.shutdown();
        assertEquals(1, ok, "同一批只审过一次");
        assertEquals(2, jdbc.queryForObject("SELECT COALESCE(SUM(change_amount), 0) FROM point_record WHERE volunteer_id = ? AND source_type = ?",
                Integer.class, me, PointSourceType.ALBUM));
    }

    @Test
    void eightThreadsOpenTheSameActivityAlbum_oneAlbum() throws Exception {
        Long aid = activityService.publish(base(20), ADMIN);
        CyclicBarrier barrier = new CyclicBarrier(8);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return albumService.albumOfActivity(aid).getId();
            }));
        }
        Set<Long> ids = Collections.synchronizedSet(new java.util.HashSet<>());
        for (Future<Long> f : futures) {
            ids.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(1, ids.size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM activity_album WHERE activity_id = ? AND is_deleted = 0", Integer.class, aid));
    }

    @Tag("stress")
    @Test
    void albumChurn_capsAndLedgerStayConsistent() throws Exception {
        Long aid = activityService.publish(base(30), ADMIN);
        List<Long> people = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            people.add(enrolledVolunteer(aid));
        }
        Long albumId = albumService.albumOfActivity(aid).getId();
        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        Random rnd = new Random(59L);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            int roll = rnd.nextInt(10);
            Long who = people.get(rnd.nextInt(people.size()));
            int size = 1 + rnd.nextInt(12);
            tasks.add(() -> {
                String kind = roll < 4 ? "上传" : roll < 5 ? "删照片" : roll < 8 ? "通过" : roll < 9 ? "驳回" : "看相册";
                try {
                    switch (kind) {
                        case "上传" -> albumService.upload(who, albumId, noSync(size));
                        case "删照片" -> {
                            Long p = jdbc.query("SELECT id FROM activity_album_photo WHERE album_id = ? AND is_deleted = 0 ORDER BY RAND() LIMIT 1",
                                    rs -> rs.next() ? rs.getLong(1) : null, albumId);
                            if (p == null) {
                                throw new BusinessException("没有照片可删");
                            }
                            albumService.deletePhoto(ADMIN, p);
                        }
                        case "通过", "驳回" -> {
                            Long b = jdbc.query("SELECT id FROM activity_album_batch WHERE album_id = ? AND points_status = 0 ORDER BY RAND() LIMIT 1",
                                    rs -> rs.next() ? rs.getLong(1) : null, albumId);
                            if (b == null) {
                                throw new BusinessException("没有待审的批次");
                            }
                            if (kind.equals("通过")) {
                                albumService.approvePoints(ADMIN, b);
                            } else {
                                albumService.rejectPoints(ADMIN, b, "压测驳回");
                            }
                        }
                        default -> {
                            albumService.detail(albumId, who);
                            albumService.photos(albumId, page(30));
                            albumService.batches(albumId, page(30));
                        }
                    }
                    outcomes.computeIfAbsent(kind + "·成功", k -> new AtomicInteger()).incrementAndGet();
                } catch (BusinessException e) {
                    outcomes.computeIfAbsent(kind + "·拒绝·" + e.getMessage(), k -> new AtomicInteger()).incrementAndGet();
                } catch (Exception e) {
                    failures.add(kind + " → " + e);
                }
            });
        }
        long t0 = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable r : tasks) {
            futures.add(pool.submit(r));
        }
        for (Future<?> f : futures) {
            f.get(300, TimeUnit.SECONDS);
        }
        pool.shutdown();
        System.out.println("==== 活动相册压测：" + tasks.size() + " 次 / 16 线程 / " + (System.currentTimeMillis() - t0)
                + " ms ====\n  " + new TreeMap<>(outcomes));
        assertTrue(failures.isEmpty(), "除业务拒绝外不应有任何异常：" + failures);
        for (Long p : people) {
            Integer awarded = jdbc.queryForObject("SELECT COALESCE(SUM(awarded_points), 0) FROM activity_album_batch WHERE album_id = ? AND uploader_id = ?",
                    Integer.class, albumId, p);
            Integer ledger = jdbc.queryForObject("SELECT COALESCE(SUM(change_amount), 0) FROM point_record WHERE volunteer_id = ? AND source_type = ?",
                    Integer.class, p, PointSourceType.ALBUM);
            assertTrue(awarded <= 10, "志愿者 " + p + " 在这个相册拿了 " + awarded + " 分，越过上限");
            assertEquals(awarded, ledger, "志愿者 " + p + " 的批次记分与账本一致");
        }
        Set<Integer> statuses = jdbc.queryForList("SELECT DISTINCT points_status FROM activity_album_batch WHERE album_id = ?", Integer.class, albumId)
                .stream().collect(Collectors.toSet());
        assertTrue(Set.of(AlbumService.POINTS_PENDING, AlbumService.POINTS_APPROVED, AlbumService.POINTS_REJECTED).containsAll(statuses));
    }

    static void ignore(ExecutionException e) {
        assertInstanceOf(BusinessException.class, e.getCause());
    }
}
