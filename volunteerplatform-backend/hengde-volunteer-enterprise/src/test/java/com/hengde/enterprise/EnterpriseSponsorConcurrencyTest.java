package com.hengde.enterprise;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.entity.MallOrder;
import com.hengde.donate.service.MallGoodsService;
import com.hengde.donate.service.MallOrderService;
import com.hengde.enterprise.dto.EnterprisePointDTOs;
import com.hengde.enterprise.service.EnterpriseAdminService;
import com.hengde.enterprise.service.EnterprisePointService;
import com.hengde.enterprise.service.EnterpriseSponsorService;
import org.junit.jupiter.api.Tag;
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
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hengde.enterprise.EnterpriseTestSupport.ADMIN;
import static com.hengde.enterprise.SponsorTestSupport.goods;
import static com.hengde.enterprise.SponsorTestSupport.normalEnterprise;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 爱心企业批·商品段的并发（前四条<b>必跑</b>）与压测（{@code @Tag("stress")}）。
 *
 * <p>① <b>下单等在途的「暂停赞助企业」并看到它</b>：裸 JDBC 事务先把商品的赞助方置为不可用不提交，下单必须被挡住，提交后兑换失败——
 * 只在列表里藏起来是挡不住直接拿规格 id 下单的，承重的是扣库存那条 UPDATE ... JOIN 里的条件。
 * ② 4 个补记同时跑：每张单只记一次。③ 10 笔扣减同时抢 50 分余额：恰好成 5 笔、余额不为负。④ 同一个幂等键 8 次同时提交：只记一笔。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class EnterpriseSponsorConcurrencyTest {

    @Autowired
    private EnterpriseAdminService adminService;
    @Autowired
    private EnterpriseSponsorService sponsorService;
    @Autowired
    private EnterprisePointService pointService;
    @Autowired
    private MallGoodsService goodsService;
    @Autowired
    private MallOrderService orderService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void placingAnOrderWaitsForAnInFlightSponsorSuspension_andIsRefused() throws Exception {
        Long ent = normalEnterprise(adminService, "暂停赛跑企业");
        Long goodsId = approvedGoods(ent, 10, 20);
        Long spec = SponsorTestSupport.specOf(jdbc, goodsId);
        Long buyer = buyer(100);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection pauser = dataSource.getConnection()) {
            pauser.setAutoCommit(false);
            try (PreparedStatement ps = pauser.prepareStatement("UPDATE mall_goods SET sponsor_suspended = 1 WHERE id = ?")) {
                ps.setLong(1, goodsId);
                assertEquals(1, ps.executeUpdate());
            }
            Future<MallOrder> placing = pool.submit(() -> orderService.placeOrder(buyer, spec));
            assertThrows(TimeoutException.class, () -> placing.get(1500, TimeUnit.MILLISECONDS), "下单应当在等暂停提交");
            pauser.commit();
            ExecutionException e = assertThrows(ExecutionException.class, () -> placing.get(10, TimeUnit.SECONDS));
            assertInstanceOf(BusinessException.class, e.getCause(), String.valueOf(e.getCause()));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(20, jdbc.queryForObject("SELECT stock FROM mall_goods_spec WHERE id = ?", Integer.class, spec), "库存没动");
    }

    @Test
    void fourCreditRunsAtOnce_eachOrderCreditedOnce() throws Exception {
        Long ent = normalEnterprise(adminService, "补记赛跑企业");
        Long goodsId = approvedGoods(ent, 7, 100);
        LocalDateTime since = LocalDateTime.now().minusMinutes(5);
        int orders = 30;
        for (int i = 0; i < orders; i++) {
            pickedOrder(goodsId);
        }
        int n = 4;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                barrier.await();
                return pointService.creditPickedOrders(since);
            }));
        }
        for (Future<Integer> f : fs) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertEquals(orders, jdbc.queryForObject("SELECT COUNT(*) FROM enterprise_point_record WHERE enterprise_id = ?", Integer.class, ent));
        assertEquals(orders * 7L, pointService.summary(ent).getBalance());
    }

    @Test
    void tenDeductionsRaceForFiftyPoints_exactlyFiveSucceed() throws Exception {
        Long ent = normalEnterprise(adminService, "扣减赛跑企业");
        pointService.adjust(ent, EnterpriseSponsorTest.adjust(50, "预置", "seed-" + EnterpriseTestSupport.next()), ADMIN);
        int n = 10;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String rid = "cut-" + EnterpriseTestSupport.next();
            fs.add(pool.submit(() -> {
                barrier.await();
                return pointService.adjust(ent, EnterpriseSponsorTest.adjust(-10, "兑换权益", rid), ADMIN);
            }));
        }
        int ok = 0;
        for (Future<?> f : fs) {
            try {
                f.get(60, TimeUnit.SECONDS);
                ok++;
            } catch (ExecutionException e) {
                assertInstanceOf(BusinessException.class, e.getCause(), String.valueOf(e.getCause()));
                assertTrue(e.getCause().getMessage().contains("余额不足"), e.getCause().getMessage());
            }
        }
        pool.shutdownNow();
        assertEquals(5, ok);
        assertEquals(0L, pointService.summary(ent).getBalance());
    }

    @Test
    void sameRequestIdSubmittedEightTimesAtOnce_recordedOnce() throws Exception {
        Long ent = normalEnterprise(adminService, "重放企业");
        String rid = "same-" + EnterpriseTestSupport.next();
        int n = 8;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                barrier.await();
                return pointService.adjust(ent, EnterpriseSponsorTest.adjust(7, "补发", rid), ADMIN);
            }));
        }
        for (Future<?> f : fs) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM enterprise_point_record WHERE request_id = ?", Integer.class, rid));
        assertEquals(7L, pointService.summary(ent).getBalance());
    }

    /**
     * 压测：3 家企业各一件赞助商品，40 个志愿者乱序下单，管理员审核 / 核销，企业被乱序暂停恢复，补记与后台扣减穿插。
     * 断言：库存守恒（初始库存＝剩余库存＋没被驳回取消的单）；补记跑完后兑换入账条数＝已领取且商品分大于 0 的单数；每家余额不为负；零非业务异常。
     */
    @Tag("stress")
    @Test
    void sponsorChurn_invariantsHold() throws Exception {
        int stock = 60;
        List<Long> ents = new ArrayList<>();
        List<Long> specs = new ArrayList<>();
        List<Long> goodsIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Long ent = normalEnterprise(adminService, "压测企业");
            Long g = approvedGoods(ent, 5 + i, stock);
            ents.add(ent);
            goodsIds.add(g);
            specs.add(SponsorTestSupport.specOf(jdbc, g));
        }
        List<Long> buyers = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            buyers.add(buyer(1000));
        }
        LocalDateTime since = LocalDateTime.now().minusMinutes(5);
        List<Long> orders = new CopyOnWriteArrayList<>();
        Map<String, AtomicInteger> tally = new ConcurrentHashMap<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        AtomicInteger seq = new AtomicInteger();
        int ops = 800;
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long start = System.currentTimeMillis();
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(pool.submit(() -> {
                Random rnd = new Random();
                while (seq.getAndIncrement() < ops) {
                    int kind = rnd.nextInt(100);
                    int which = rnd.nextInt(3);
                    String label;
                    try {
                        if (kind < 40) {
                            label = "下单";
                            orders.add(orderService.placeOrder(buyers.get(rnd.nextInt(buyers.size())), specs.get(which)).getId());
                        } else if (kind < 60) {
                            label = "审核";
                            if (orders.isEmpty()) {
                                throw new BusinessException("还没有单");
                            }
                            orderService.approve(orders.get(rnd.nextInt(orders.size())), ADMIN);
                        } else if (kind < 75) {
                            label = "核销";
                            if (orders.isEmpty()) {
                                throw new BusinessException("还没有单");
                            }
                            String code = jdbc.queryForObject("SELECT COALESCE(pickup_code, '') FROM mall_order WHERE id = ?", String.class,
                                    orders.get(rnd.nextInt(orders.size())));
                            if (code.isEmpty()) {
                                throw new BusinessException("还没审核");
                            }
                            orderService.verify(code, ADMIN);
                        } else if (kind < 82) {
                            label = "暂停";
                            adminService.pause(ents.get(which), "压测", ADMIN);
                        } else if (kind < 89) {
                            label = "恢复";
                            adminService.resume(ents.get(which), ADMIN);
                        } else if (kind < 95) {
                            label = "补记";
                            pointService.creditPickedOrders(since);
                        } else {
                            label = "扣减";
                            pointService.adjust(ents.get(which), EnterpriseSponsorTest.adjust(-(1 + rnd.nextInt(10)), "压测扣减",
                                    "st-" + EnterpriseTestSupport.next()), ADMIN);
                        }
                        tally.computeIfAbsent(label + "·成功", k -> new AtomicInteger()).incrementAndGet();
                    } catch (BusinessException e) {
                        tally.computeIfAbsent("拒绝·" + e.getMessage().replaceAll("\\d", "#"), k -> new AtomicInteger()).incrementAndGet();
                    } catch (Throwable e) {
                        unexpected.add(e);
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : fs) {
            f.get(5, TimeUnit.MINUTES);
        }
        pool.shutdownNow();
        System.out.println("==== 爱心企业赞助商品压测：" + ops + " 次 / " + threads + " 线程 / " + (System.currentTimeMillis() - start)
                + " ms ====\n  " + new TreeMap<>(tally));
        assertTrue(unexpected.isEmpty(), "不应有非业务异常：" + unexpected.stream().limit(3).map(String::valueOf).toList());
        for (int i = 0; i < 3; i++) {
            Integer left = jdbc.queryForObject("SELECT stock FROM mall_goods_spec WHERE id = ?", Integer.class, specs.get(i));
            Integer live = jdbc.queryForObject("SELECT COUNT(*) FROM mall_order WHERE spec_id = ? AND status NOT IN (2, 4)", Integer.class, specs.get(i));
            assertEquals(stock, left + live, "库存守恒：第 " + i + " 家");
        }
        pointService.creditPickedOrders(since);
        for (int i = 0; i < 3; i++) {
            Integer picked = jdbc.queryForObject("SELECT COUNT(*) FROM mall_order WHERE goods_id = ? AND status = 3 AND points - COALESCE(shipping_points, 0) > 0",
                    Integer.class, goodsIds.get(i));
            Integer credited = jdbc.queryForObject("SELECT COUNT(*) FROM enterprise_point_record WHERE enterprise_id = ? AND source_type = 1",
                    Integer.class, ents.get(i));
            assertEquals(picked, credited, "兑换入账条数＝已领取的单数：第 " + i + " 家");
            assertTrue(pointService.summary(ents.get(i)).getBalance() >= 0, "余额不为负");
        }
        assertTrue(tally.getOrDefault("核销·成功", new AtomicInteger()).get() > 0 && tally.getOrDefault("暂停·成功", new AtomicInteger()).get() > 0,
                String.valueOf(tally));
    }

    // ================= 造数 =================

    private Long approvedGoods(Long ent, int points, int stock) {
        Long id = sponsorService.createGoods(ent, goods("并发赞助商品", points, stock));
        goodsService.submitForSponsor(id, ent);
        goodsService.approve(id, ADMIN);
        return id;
    }

    private Long buyer(int points) {
        Long v = SponsorTestSupport.volunteer(volunteerMapper, cryptoUtil, EnterpriseTestSupport.phone());
        SponsorTestSupport.givePoints(jdbc, v, points);
        return v;
    }

    private void pickedOrder(Long goodsId) {
        MallOrder o = orderService.placeOrder(buyer(100), SponsorTestSupport.specOf(jdbc, goodsId));
        orderService.approve(o.getId(), ADMIN);
        orderService.verify(jdbc.queryForObject("SELECT pickup_code FROM mall_order WHERE id = ?", String.class, o.getId()), ADMIN);
    }
}
