package com.hengde.social;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.social.dao.SocialPostMapper;
import com.hengde.social.service.SocialCommentService;
import com.hengde.social.service.SocialInteractionService;
import com.hengde.social.service.SocialPostService;
import com.hengde.social.service.SocialUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 社区的并发（全部<b>必跑</b>）：点赞 / 评论计数与行数对得上、连点不多记、同时删同一条评论只减一次、关注只成一行、
 * 发帖在处置闸门的父行锁上排队。
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class SocialConcurrencyTest extends SocialTestSupport {

    @Autowired
    private SocialPostService postService;
    @Autowired
    private SocialInteractionService interactionService;
    @Autowired
    private SocialCommentService commentService;
    @Autowired
    private SocialUserService userService;
    @Autowired
    private SocialPostMapper postMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private org.redisson.api.RedissonClient redissonClient;

    /**
     * 同一个人的点赞 / 取消 / 关注 / 取关必须在「人」这把锁上排队。连点死锁靠赛跑不一定撞得出来（变异验证时去掉锁照样绿过），
     * 所以由测试线程直接持住那把锁，断言这几个写操作都在等它。
     */
    @Test
    void likeAndFollowWaitForThePersonLock() throws Exception {
        Long me = member();
        Long post = postService.publish(member(), text("排队点赞"));
        Long other = member();
        List<Callable<Object>> writes = List.of(
                () -> interactionService.like(me, post),
                () -> interactionService.unlike(me, post),
                () -> userService.follow(me, other),
                () -> userService.unfollow(me, other));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < writes.size(); i++) {
                org.redisson.api.RLock lock = redissonClient.getLock(SocialInteractionService.LOCK_PREFIX + me);
                lock.lock();
                Future<Object> f;
                try {
                    f = pool.submit(writes.get(i));
                    int idx = i;
                    assertThrows(TimeoutException.class, () -> f.get(800, TimeUnit.MILLISECONDS), "第 " + idx + " 个写操作应当在等人维度的锁");
                } finally {
                    lock.unlock();
                }
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sixteenPeopleLikeAtOnce_countMatchesRows() throws Exception {
        Long post = postService.publish(member(), text("一起点赞"));
        List<Long> people = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            people.add(member());
        }
        runAll(people.stream().<Callable<Object>>map(p -> () -> interactionService.like(p, post)).toList());
        assertEquals(16, postMapper.selectById(post).getLikeCount());
        assertEquals(16, likeRows(post));
    }

    @Test
    void samePersonMashesLikeAndUnlike_countStaysConsistent() throws Exception {
        Long post = postService.publish(member(), text("连点"));
        Long me = member();
        List<Callable<Object>> likes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            likes.add(() -> interactionService.like(me, post));
        }
        runAll(likes);
        assertEquals(1, postMapper.selectById(post).getLikeCount(), "连点 10 次只记 1 次");
        List<Callable<Object>> mixed = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            mixed.add(i % 2 == 0 ? () -> interactionService.like(me, post) : () -> interactionService.unlike(me, post));
        }
        runAll(mixed);
        assertEquals(likeRows(post), (long) postMapper.selectById(post).getLikeCount(), "点赞量始终等于点赞行数");
    }

    @Test
    void commentsAndDoubleDelete_countOnce() throws Exception {
        Long author = member();
        Long post = postService.publish(author, text("一起评论"));
        List<Callable<Object>> comments = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Long p = member();
            int k = i;
            comments.add(() -> commentService.comment(p, post, commentOf("第 " + k + " 条", null)));
        }
        runAll(comments);
        assertEquals(20, postMapper.selectById(post).getCommentCount());

        Long commenter = member();
        Long target = commentService.comment(commenter, post, commentOf("要被两个人同时删", null));
        List<Callable<Object>> deletes = List.of(
                () -> {
                    commentService.delete(commenter, target);
                    return null;
                },
                () -> {
                    commentService.delete(author, target);
                    return null;
                });
        runAll(deletes);
        assertEquals(20, postMapper.selectById(post).getCommentCount(), "同时删同一条评论只减一次");
    }

    @Test
    void samePairFollowsFromEightThreads_oneRow() throws Exception {
        Long a = member();
        Long b = member();
        List<Callable<Object>> follows = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            follows.add(() -> userService.follow(a, b));
        }
        runAll(follows);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM social_follow WHERE follower_id = ? AND followee_id = ?",
                Integer.class, a, b));
        List<Callable<Object>> mixed = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            mixed.add(i % 2 == 0 ? () -> userService.follow(a, b) : () -> userService.unfollow(a, b));
        }
        runAll(mixed);
        assertEquals(true, jdbc.queryForObject("SELECT COUNT(*) FROM social_follow WHERE follower_id = ? AND followee_id = ?",
                Integer.class, a, b) <= 1, "连点关注 / 取关不死锁、至多一行");
    }

    /**
     * 发帖要在处置闸门的父行锁上排队：奖惩终审给这个人上「禁止发帖」的事务还没提交时（它先锁住志愿者行），发帖必须等它，
     * 等到之后看到的是「已被禁言」。用裸 JDBC 事务锁住志愿者行、插一条处置不提交，确定性地摆出这个时序。
     */
    @Test
    void publishWaitsForAnUncommittedSanction_andSeesIt() throws Exception {
        Long me = member();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement lock = conn.prepareStatement("SELECT id FROM volunteer WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, me);
                lock.executeQuery();
            }
            try (PreparedStatement ins = conn.prepareStatement("INSERT INTO volunteer_sanction (volunteer_id, source_type, source_id, "
                    + "scope, status, effective_time, expire_time, create_time, is_deleted) "
                    + "VALUES (?, 1, ?, 4, 1, NOW() - INTERVAL 1 MINUTE, NOW() + INTERVAL 3 DAY, NOW(), 0)")) {
                ins.setLong(1, me);
                ins.setLong(2, SEQ.incrementAndGet() + 8_000_000L);
                ins.executeUpdate();
            }
            Future<Long> f = pool.submit(() -> postService.publish(me, text("赶在禁言之前发")));
            assertThrows(TimeoutException.class, () -> f.get(1500, TimeUnit.MILLISECONDS), "发帖应当在等处置写入方");
            conn.commit();
            ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(30, TimeUnit.SECONDS));
            assertInstanceOf(BusinessException.class, e.getCause());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM social_post WHERE author_type = 1 AND author_id = ?", Integer.class, me));
    }

    private long likeRows(Long post) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM social_post_like WHERE post_id = ?", Long.class, post);
    }

    private static void runAll(List<Callable<Object>> tasks) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> t : tasks) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return t.call();
                }));
            }
            for (Future<Object> f : futures) {
                try {
                    f.get(60, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    assertInstanceOf(BusinessException.class, e.getCause(), "只允许业务拒绝：" + e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertNotNull(tasks);
    }
}
