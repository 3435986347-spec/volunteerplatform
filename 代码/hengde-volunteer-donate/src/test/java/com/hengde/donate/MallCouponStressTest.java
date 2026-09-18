package com.hengde.donate;

import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.service.PointService;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.MallCouponGrantStatus;
import com.hengde.donate.constant.MallCouponType;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.constant.MallOrderStatus;
import com.hengde.donate.dto.MallCouponGrantDTO;
import com.hengde.donate.dto.MallCouponSaveDTO;
import com.hengde.donate.entity.MallOrder;
import com.hengde.donate.service.MallCouponService;
import com.hengde.donate.service.MallOrderService;
import com.hengde.donate.vo.CouponGrantResultVO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卷批<b>压力测试</b>：几十到几百个并发请求下，四本账（订单 / 库存 / 卷 / 积分）始终对得上。
 *
 * <p><b>默认不跑</b>（{@code @Tag("stress")}，surefire 默认排除）。要跑：</p>
 * <pre>./mvnw test -Dtest.excludedGroups=none -Dgroups=stress -f ../hengde-volunteer-donate/pom.xml</pre>
 *
 * <p>三条纪律（CLAUDE.md 积分账本那一节写死过的）：</p>
 * <ul>
 *   <li>结果一律 {@code Future.get()} 收——catch 掉只看最终余额，6 次里 5 次抛异常也能「通过」；</li>
 *   <li><b>只有 {@link BusinessException} 是可接受的失败</b>（库存不足、状态不可取消……），
 *       死锁、唯一键冲突漏网、空指针一律算红；</li>
 *   <li>断言的是<b>不变式</b>，不是「成功了多少单」——成功数随调度变化，不变式不该变。</li>
 * </ul>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@Tag("stress")
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class MallCouponStressTest {

    private static final long ADMIN = 7301L;
    private static final AtomicLong PHONE_SEQ = new AtomicLong(System.nanoTime() % 10_000_000L + 60_000_000L);

    @Autowired
    private MallOrderService orderService;
    @Autowired
    private MallCouponService couponService;
    @Autowired
    private PointService pointService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;

    /**
     * 抢购：80 个人各持一张满减卷，同时抢 20 件库存。
     *
     * <p>不变式：恰好 20 单成交、库存 0、恰好 20 张卷被用掉且每张都指向一张成交单、
     * 成交者余额 = 初始 - 实付、没抢到的人余额分文不动、卷原样还在。</p>
     */
    @Test
    void flashSaleWithCouponsNeverOversellsOrLeaksACoupon() throws Exception {
        long goodsId = 996_001L;
        long specId = 996_101L;
        int stock = 20;
        int buyers = 80;
        seedGoods(goodsId, specId, 30, stock);
        Long couponId = discountCoupon(20, 12);
        List<Long> people = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            Long v = insertVolunteer();
            givePoints(v, 100);
            people.add(v);
        }
        grant(couponId, people);
        Map<Long, Long> grantOf = grantIdsByVolunteer(couponId);

        long start = System.nanoTime();
        List<Outcome> outcomes = runConcurrently(buyers, i -> {
            Long v = people.get(i);
            MallOrder o = orderService.placeOrder(v, specId, grantOf.get(v));
            return o.getId();
        });
        long ms = (System.nanoTime() - start) / 1_000_000;

        long ok = outcomes.stream().filter(o -> o.orderId != null).count();
        report("抢购 80 抢 20", buyers, ms, ok);
        assertEquals(stock, ok, "库存 20 只能成交 20 单");
        assertEquals(0, stockOf(specId), "库存必须落在 0");
        assertEquals(stock, jdbc.queryForObject(
                "SELECT COUNT(*) FROM mall_coupon_grant WHERE coupon_id = ? AND status = ?",
                Integer.class, couponId, MallCouponGrantStatus.USED), "恰好 20 张卷被用掉");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM mall_coupon_grant g LEFT JOIN mall_order o ON o.id = g.used_order_id "
                        + "WHERE g.coupon_id = ? AND g.status = ? AND (o.id IS NULL OR o.coupon_grant_id <> g.id)",
                Integer.class, couponId, MallCouponGrantStatus.USED), "每张用掉的卷都必须指向一张用了它的单");
        for (Outcome o : outcomes) {
            Long v = people.get(o.index);
            int expected = o.orderId != null ? 100 - (30 - 12) : 100;
            assertEquals(expected, pointService.balanceOf(v), "志愿者 " + v + " 的余额不对");
        }
    }

    /**
     * 同一批人、同一个 requestId，8 个线程同时重放一次 400 人的批量发卷。
     *
     * <p>不变式：库里恰好 400 张；每次调用都如实报「新发 + 已发过 = 400」；所有调用的「新发」之和 = 400。
     * 这条盯的是 {@code uk_request_volunteer} 撞键后的当前读复核——快照读取不回对方刚提交的行时，
     * 会被误判成「载荷冲突」而整批报错。</p>
     */
    @Test
    void replayStormOfTheSameBatchGrantIssuesExactlyOnce() throws Exception {
        Long couponId = discountCoupon(20, 5);
        List<Long> people = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            people.add(insertVolunteer());
        }
        String rid = "rid-storm-" + System.nanoTime();
        int threads = 8;

        long start = System.nanoTime();
        List<Future<CouponGrantResultVO>> futures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        try {
            for (int t = 0; t < threads; t++) {
                // 每个线程打乱顺序提交——服务端排序后插入，才不会在唯一键上互等成死锁
                List<Long> shuffled = new ArrayList<>(people);
                Collections.shuffle(shuffled);
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    MallCouponGrantDTO d = new MallCouponGrantDTO();
                    d.setRequestId(rid);
                    d.setVolunteerIds(shuffled);
                    return couponService.grant(couponId, d, ADMIN);
                }));
            }
            int granted = 0;
            for (Future<CouponGrantResultVO> f : futures) {
                CouponGrantResultVO r = f.get(120, TimeUnit.SECONDS);
                assertEquals(400, r.getGranted() + r.getAlreadyGranted(),
                        "每次调用都要把 400 人如实归入「新发」或「已发过」");
                granted += r.getGranted();
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            report("批量发卷重放风暴 8×400", threads, ms, granted);
            assertEquals(400, granted, "所有调用新发之和必须恰好 400");
            assertEquals(400, jdbc.queryForObject("SELECT COUNT(*) FROM mall_coupon_grant WHERE request_id = ?",
                    Integer.class, rid));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 混合搅拌：30 人反复「下单（随机用卷）/ 取消」，同时一个管理员线程在随机通过或驳回待审单。
     *
     * <p>这是最贴近线上的形态——同一张单可能同时被本人取消、被管理员驳回、被管理员通过。
     * 四本账的不变式在结束时逐条核对。</p>
     */
    @Test
    void mixedChurnKeepsAllFourLedgersConsistent() throws Exception {
        long goodsId = 996_002L;
        long specId = 996_201L;
        int initialStock = 40;
        int people = 30;
        int initialPoints = 300;
        seedGoods(goodsId, specId, 30, initialStock);
        Long couponA = discountCoupon(20, 5);
        Long couponB = discountCoupon(20, 5);
        List<Long> volunteers = new ArrayList<>();
        for (int i = 0; i < people; i++) {
            Long v = insertVolunteer();
            givePoints(v, initialPoints);
            volunteers.add(v);
        }
        grant(couponA, volunteers);
        grant(couponB, volunteers);
        Map<Long, Long> grantA = grantIdsByVolunteer(couponA);
        Map<Long, Long> grantB = grantIdsByVolunteer(couponB);

        AtomicInteger ops = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(people + 1);
        CyclicBarrier barrier = new CyclicBarrier(people + 1);
        List<Future<?>> futures = new ArrayList<>();
        long start = System.nanoTime();
        try {
            for (Long v : volunteers) {
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    for (int k = 0; k < 12; k++) {
                        try {
                            if (rnd.nextInt(3) == 0) {
                                Long pending = anyPendingOrderOf(v);
                                if (pending != null) {
                                    orderService.cancel(pending, v);
                                }
                            } else {
                                Long g = switch (rnd.nextInt(3)) {
                                    case 0 -> grantA.get(v);
                                    case 1 -> grantB.get(v);
                                    default -> null;
                                };
                                orderService.placeOrder(v, specId, g);
                            }
                        } catch (BusinessException expected) {
                            // 库存不足 / 卷已被使用 / 状态不可取消……都是业务上正常的拒绝
                        } catch (Throwable t) {
                            unexpected.add(t);
                        }
                        ops.incrementAndGet();
                    }
                    return null;
                }));
            }
            futures.add(pool.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int k = 0; k < 150; k++) {
                    List<Long> pending = jdbc.queryForList(
                            "SELECT id FROM mall_order WHERE spec_id = ? AND status = ? LIMIT 20",
                            Long.class, specId, MallOrderStatus.PENDING);
                    if (pending.isEmpty()) {
                        Thread.sleep(5);
                        continue;
                    }
                    Long id = pending.get(rnd.nextInt(pending.size()));
                    try {
                        if (rnd.nextBoolean()) {
                            orderService.approve(id, ADMIN);
                        } else {
                            orderService.reject(id, "压测驳回", ADMIN);
                        }
                    } catch (BusinessException expected) {
                        // 被本人抢先取消了
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                    ops.incrementAndGet();
                }
                return null;
            }));
            for (Future<?> f : futures) {
                f.get(180, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        report("混合搅拌 30 人 + 1 审核员", people + 1, ms, ops.get());

        assertTrue(unexpected.isEmpty(), "只允许业务拒绝，出现了意外异常：" + unexpected);

        // ① 库存：当前库存 + 占着库存的单（待审 / 待领 / 已领）= 初始库存
        int active = jdbc.queryForObject("SELECT COUNT(*) FROM mall_order WHERE spec_id = ? AND status IN (?, ?, ?)",
                Integer.class, specId, MallOrderStatus.PENDING, MallOrderStatus.READY, MallOrderStatus.PICKED);
        assertEquals(initialStock, stockOf(specId) + active, "库存守恒");

        // ② 卷：被用掉的卷 = 用了卷的有效单；且一一对应
        int usedGrants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mall_coupon_grant WHERE coupon_id IN (?, ?) AND status = ?",
                Integer.class, couponA, couponB, MallCouponGrantStatus.USED);
        int activeWithCoupon = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mall_order WHERE spec_id = ? AND coupon_grant_id IS NOT NULL AND status IN (?, ?, ?)",
                Integer.class, specId, MallOrderStatus.PENDING, MallOrderStatus.READY, MallOrderStatus.PICKED);
        assertEquals(activeWithCoupon, usedGrants, "被占用的卷数必须等于用了卷的有效单数");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM mall_order o JOIN mall_coupon_grant g ON g.id = o.coupon_grant_id "
                        + "WHERE o.spec_id = ? AND o.status IN (?, ?, ?) AND (g.status <> ? OR g.used_order_id <> o.id)",
                Integer.class, specId, MallOrderStatus.PENDING, MallOrderStatus.READY, MallOrderStatus.PICKED,
                MallCouponGrantStatus.USED), "有效单用的卷必须处于「用在本单上」的状态");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM mall_order o JOIN mall_coupon_grant g ON g.used_order_id = o.id "
                        + "WHERE o.spec_id = ? AND o.status IN (?, ?)",
                Integer.class, specId, MallOrderStatus.CANCELLED, MallOrderStatus.REJECTED),
                "已取消 / 已驳回的单不得还占着卷");

        // ③ 积分：每人余额 = 初始 - 有效单实付之和；且不为负
        for (Long v : volunteers) {
            Integer spent = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(points), 0) FROM mall_order WHERE volunteer_id = ? AND status IN (?, ?, ?)",
                    Integer.class, v, MallOrderStatus.PENDING, MallOrderStatus.READY, MallOrderStatus.PICKED);
            int balance = pointService.balanceOf(v);
            assertEquals(initialPoints - spent, balance, "志愿者 " + v + " 的账实不符");
            assertTrue(balance >= 0, "余额不得为负");
        }
    }

    // ---------------- harness ----------------

    private record Outcome(int index, Long orderId) {
    }

    @FunctionalInterface
    private interface IndexedCall {
        Long call(int index) throws Exception;
    }

    /** 屏障齐发；业务拒绝记为失败结果，其余异常原样经 {@code Future.get()} 抛出使用例变红。 */
    private List<Outcome> runConcurrently(int n, IndexedCall call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    try {
                        return new Outcome(idx, call.call(idx));
                    } catch (BusinessException rejected) {
                        return new Outcome(idx, null);
                    }
                }));
            }
            List<Outcome> out = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                out.add(f.get(120, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void report(String name, int concurrency, long ms, long count) {
        System.out.printf("[STRESS] %s：并发 %d，耗时 %d ms，计数 %d，约 %.1f 次/秒%n",
                name, concurrency, ms, count, ms == 0 ? 0.0 : count * 1000.0 / ms);
    }

    private Long anyPendingOrderOf(Long volunteerId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM mall_order WHERE volunteer_id = ? AND status = ? LIMIT 1",
                Long.class, volunteerId, MallOrderStatus.PENDING);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void seedGoods(long goodsId, long specId, int price, int stock) {
        jdbc.update("DELETE FROM mall_goods_spec WHERE goods_id = ?", goodsId);
        jdbc.update("DELETE FROM mall_goods WHERE id = ?", goodsId);
        jdbc.update("INSERT INTO mall_goods (id, name, status, hidden, sort, create_time, update_time, is_deleted) "
                + "VALUES (?, '压测商品', ?, 0, 0, NOW(), NOW(), 0)", goodsId, MallGoodsStatus.ON_SALE);
        jdbc.update("INSERT INTO mall_goods_spec (id, goods_id, name, points, stock, sort, create_time, update_time, "
                + "is_deleted) VALUES (?, ?, '标准', ?, ?, 0, NOW(), NOW(), 0)", specId, goodsId, price, stock);
    }

    private Long discountCoupon(int threshold, int discount) {
        MallCouponSaveDTO d = new MallCouponSaveDTO();
        d.setName("压测卷-" + System.nanoTime());
        d.setType(MallCouponType.DISCOUNT);
        d.setThresholdPoints(threshold);
        d.setDiscountPoints(discount);
        d.setValidStart(LocalDateTime.now().minusDays(1));
        d.setValidEnd(LocalDateTime.now().plusDays(30));
        return couponService.create(d, ADMIN);
    }

    private void grant(Long couponId, List<Long> people) {
        MallCouponGrantDTO d = new MallCouponGrantDTO();
        d.setRequestId("rid-stress-" + System.nanoTime());
        d.setVolunteerIds(people);
        assertEquals(people.size(), couponService.grant(couponId, d, ADMIN).getGranted());
    }

    private Map<Long, Long> grantIdsByVolunteer(Long couponId) {
        Map<Long, Long> m = new java.util.HashMap<>();
        jdbc.query("SELECT id, volunteer_id FROM mall_coupon_grant WHERE coupon_id = ?",
                rs -> {
                    m.put(rs.getLong("volunteer_id"), rs.getLong("id"));
                }, couponId);
        return m;
    }

    private Long insertVolunteer() {
        String phone = String.format("133%08d", PHONE_SEQ.incrementAndGet() % 100_000_000L);
        Volunteer v = new Volunteer();
        v.setOpenid("openid_stress_" + System.nanoTime() + "_" + ThreadLocalRandom.current().nextInt(1_000_000));
        v.setRealName("压测志愿者");
        v.setPhone(cryptoUtil.encrypt(phone));
        v.setPhoneHash(cryptoUtil.hashPhone(phone));
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private void givePoints(Long volunteerId, int amount) {
        jdbc.update("INSERT INTO point_record (volunteer_id, change_amount, source_type, source_id, remark, "
                + "operator_type, create_time, update_time, is_deleted) VALUES (?, ?, ?, NULL, '压测预置', 0, NOW(), NOW(), 0)",
                volunteerId, amount, PointSourceType.MANUAL);
    }

    private int stockOf(long specId) {
        return jdbc.queryForObject("SELECT stock FROM mall_goods_spec WHERE id = ?", Integer.class, specId);
    }
}
