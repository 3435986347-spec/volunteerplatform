package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.MallCouponType;
import com.hengde.donate.dto.MallCouponGrantDTO;
import com.hengde.donate.dto.MallCouponSaveDTO;
import com.hengde.donate.service.MallCouponService;
import com.hengde.donate.vo.CouponGrantResultVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 批量发卷的<b>并发重放不得死锁</b>——必跑用例（不打 stress 标签），{@code MallCouponStressTest} 撞出来的那一个。
 *
 * <p><b>缺陷形态</b>：同一 requestId 的两次重放若同时进入事务，各自「此前已发过哪些人」的快照都读在
 * 第一个写入者提交之前，于是都去逐行 INSERT。第一个写入者一提交，两者的每一次 INSERT 都撞
 * {@code uk_request_volunteer}；撞键失败的 INSERT 在 PRIMARY supremum 上留下间隙锁，
 * 两者的下一次 INSERT 又都要在这个间隙上取插入意向锁——互等成环，{@code ER_LOCK_DEADLOCK}。</p>
 *
 * <p><b>本用例怎么把这个时序摆出来、而不是靠调度撞运气</b>：用一条裸 JDBC 事务先插入第一个人并<b>不提交</b>，
 * 两个服务层重放随后同时进入——没有 requestId 锁时，两者都会卡在第一个人那一行上（等 S 锁），
 * 等裸事务一提交，便同时撞键、同时进入「撞键 → supremum 间隙锁 → 插入意向锁」的循环。
 * 有了锁，第二个重放只能在第一个提交之后开始，其快照看得见全部已发的人，一行都不插。</p>
 *
 * <p>变异验证：把 {@code MallCouponService.grant} 里的 {@code DistributedLockSupport.runLocked}
 * 拿掉、直接 {@code transactionTemplate.execute}，本用例以 {@code DeadlockLoserDataAccessException} 变红。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class MallCouponGrantConcurrencyTest {

    private static final long ADMIN = 7401L;
    private static final int PEOPLE = 60;
    private static final AtomicLong PHONE_SEQ = new AtomicLong(System.nanoTime() % 10_000_000L + 80_000_000L);

    @Autowired
    private MallCouponService couponService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;

    @Test
    void concurrentReplaysOfTheSameBatchDoNotDeadlock() throws Exception {
        Long couponId = createCoupon();
        List<Long> people = new ArrayList<>();
        for (int i = 0; i < PEOPLE; i++) {
            people.add(insertVolunteer());
        }
        String rid = "rid-deadlock-" + System.nanoTime();
        Long first = people.get(0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            // 裸事务先把第一个人插进去、不提交：两次重放随后都会在这一行上排队
            try (PreparedStatement ps = holder.prepareStatement(
                    "INSERT INTO mall_coupon_grant (coupon_id, volunteer_id, request_id, coupon_name, type, "
                            + "threshold_points, discount_points, valid_start, expire_time, status, grant_by, "
                            + "create_time, update_time, is_deleted) "
                            + "SELECT id, ?, ?, name, type, threshold_points, discount_points, valid_start, valid_end, "
                            + "0, ?, NOW(), NOW(), 0 FROM mall_coupon WHERE id = ?")) {
                ps.setLong(1, first);
                ps.setString(2, rid);
                ps.setLong(3, ADMIN);
                ps.setLong(4, couponId);
                assertEquals(1, ps.executeUpdate());
            }

            CyclicBarrier start = new CyclicBarrier(2);
            List<Future<CouponGrantResultVO>> replays = new ArrayList<>();
            for (int t = 0; t < 2; t++) {
                replays.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    MallCouponGrantDTO d = new MallCouponGrantDTO();
                    d.setRequestId(rid);
                    d.setVolunteerIds(people);
                    return couponService.grant(couponId, d, ADMIN);
                }));
            }
            // 给两次重放足够的时间走到「卡在第一个人那一行」——没有锁时两者都卡在 DB 上，有锁时一个卡 DB、一个卡 Redisson
            Thread.sleep(1500);
            holder.commit();

            int grantedTotal = 0;
            for (Future<CouponGrantResultVO> f : replays) {
                // get() 会把死锁原样抛出来——这正是本用例要盯的
                CouponGrantResultVO r = f.get(60, TimeUnit.SECONDS);
                assertEquals(PEOPLE, r.getGranted() + r.getAlreadyGranted(), "每次重放都要把全部人如实归类");
                grantedTotal += r.getGranted();
            }
            assertEquals(PEOPLE - 1, grantedTotal, "除了裸事务插进去的那一个，其余人恰好各发一张");
            assertEquals(PEOPLE, jdbc.queryForObject("SELECT COUNT(*) FROM mall_coupon_grant WHERE request_id = ?",
                    Integer.class, rid));
        } finally {
            pool.shutdownNow();
        }
    }

    private Long createCoupon() {
        MallCouponSaveDTO d = new MallCouponSaveDTO();
        d.setName("死锁用例卷-" + System.nanoTime());
        d.setType(MallCouponType.DISCOUNT);
        d.setThresholdPoints(20);
        d.setDiscountPoints(5);
        d.setValidStart(LocalDateTime.now().minusDays(1));
        d.setValidEnd(LocalDateTime.now().plusDays(30));
        return couponService.create(d, ADMIN);
    }

    private Long insertVolunteer() {
        String phone = String.format("131%08d", PHONE_SEQ.incrementAndGet() % 100_000_000L);
        Volunteer v = new Volunteer();
        v.setOpenid("openid_grant_dl_" + System.nanoTime());
        v.setRealName("发卷死锁用例");
        v.setPhone(cryptoUtil.encrypt(phone));
        v.setPhoneHash(cryptoUtil.hashPhone(phone));
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }
}
