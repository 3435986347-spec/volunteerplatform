package com.hengde.enterprise;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.entity.MallOrder;
import com.hengde.donate.service.MallGoodsService;
import com.hengde.donate.service.MallOrderService;
import com.hengde.enterprise.dto.EnterpriseReviewDTOs;
import com.hengde.enterprise.service.EnterpriseAdminService;
import com.hengde.enterprise.service.EnterpriseReviewService;
import com.hengde.enterprise.service.EnterpriseSponsorService;
import com.hengde.enterprise.vo.EnterpriseReviewVO;
import com.hengde.social.dto.SocialDTOs;
import com.hengde.social.service.SocialPostService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hengde.enterprise.EnterpriseTestSupport.ADMIN;
import static com.hengde.enterprise.SponsorTestSupport.goods;
import static com.hengde.enterprise.SponsorTestSupport.normalEnterprise;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 爱心企业批·社区段的并发（前两条<b>必跑</b>）与压测（{@code @Tag("stress")}）。
 *
 * <p>① 同一个人对同一张兑换单 8 次同时评价：恰好成一条——承重的是生成列唯一键 {@code uk_active_order}，先查再插挡不住。
 * ② 6 个后台同时屏蔽同一条评价：恰好成一个（屏蔽是 {@code WHERE status = 正常} 的 CAS），其余被告知已被屏蔽。
 * ③ 压测：企业乱序发帖 / 改帖 / 删帖，志愿者乱序评价 / 重评，后台乱序屏蔽 / 恢复 / 删除，断言
 * 「一张兑换单至多一条没删的评价」「公开列表里没有被屏蔽的」「零异常」。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class EnterpriseSocialConcurrencyTest {

    @Autowired
    private EnterpriseAdminService adminService;
    @Autowired
    private EnterpriseSponsorService sponsorService;
    @Autowired
    private EnterpriseReviewService reviewService;
    @Autowired
    private SocialPostService postService;
    @Autowired
    private MallGoodsService goodsService;
    @Autowired
    private MallOrderService orderService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void eightConcurrentReviewsOfTheSameOrderLeaveExactlyOne() throws Exception {
        Long ent = normalEnterprise(adminService, "连点评价企业");
        Long buyer = volunteer();
        Long orderId = pickedOrder(ent, buyer);
        int n = 8;
        CyclicBarrier gate = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                int k = i;
                futures.add(pool.submit(() -> {
                    gate.await(20, TimeUnit.SECONDS);
                    try {
                        reviewService.create(buyer, ent, create(orderId, 5, "连点第 " + k + " 次"));
                        return "ok";
                    } catch (BusinessException e) {
                        return e.getMessage();
                    }
                }));
            }
            int ok = 0;
            for (Future<String> f : futures) {
                if ("ok".equals(f.get(30, TimeUnit.SECONDS))) {
                    ok++;
                }
            }
            assertEquals(1, ok, "一张兑换单只留得下一条评价");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, reviewService.listForAdmin(ent, null, new PageQuery()).getRecords().size());
    }

    @Test
    void sixConcurrentHidesOfTheSameReviewLeaveExactlyOne() throws Exception {
        Long ent = normalEnterprise(adminService, "同时屏蔽企业");
        Long buyer = volunteer();
        Long reviewId = reviewService.create(buyer, ent, create(pickedOrder(ent, buyer), 4, "同时屏蔽它"));
        int n = 6;
        CyclicBarrier gate = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                int k = i;
                futures.add(pool.submit(() -> {
                    gate.await(20, TimeUnit.SECONDS);
                    try {
                        reviewService.hide(reviewId, "第 " + k + " 个人按的", ADMIN);
                        return "ok";
                    } catch (BusinessException e) {
                        return e.getMessage();
                    }
                }));
            }
            int ok = 0;
            for (Future<String> f : futures) {
                String r = f.get(30, TimeUnit.SECONDS);
                if ("ok".equals(r)) {
                    ok++;
                } else {
                    assertTrue(r.contains("已被屏蔽"), "输的一方要被告知刚发生的事，实际：" + r);
                }
            }
            assertEquals(1, ok, "屏蔽是一条 CAS，只成一个");
        } finally {
            pool.shutdownNow();
        }
        assertTrue(reviewService.listPublic(ent, new PageQuery()).getRecords().isEmpty());
    }

    @Tag("stress")
    @Test
    void postAndReviewChurn_invariantsHold() throws Exception {
        int enterprises = 3;
        int buyersPerEnterprise = 8;
        List<Long> ents = new ArrayList<>();
        List<Long> orders = new ArrayList<>();
        List<Long> buyers = new ArrayList<>();
        List<Long> orderOwners = new ArrayList<>();
        List<Long> orderEnterprises = new ArrayList<>();
        for (int i = 0; i < enterprises; i++) {
            Long ent = normalEnterprise(adminService, "压测发帖企业");
            ents.add(ent);
            for (int j = 0; j < buyersPerEnterprise; j++) {
                Long buyer = volunteer();
                buyers.add(buyer);
                orders.add(pickedOrder(ent, buyer));
                orderOwners.add(buyer);
                orderEnterprises.add(ent);
            }
        }
        List<Long> posts = new CopyOnWriteArrayList<>();
        List<Long> reviews = new CopyOnWriteArrayList<>();
        Map<String, AtomicInteger> tally = new ConcurrentHashMap<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        AtomicInteger seq = new AtomicInteger();
        int threads = 16;
        int operations = 600;
        CyclicBarrier gate = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long started = System.currentTimeMillis();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    Random rnd = new Random(Thread.currentThread().getId() * 31 + 7);
                    gate.await(30, TimeUnit.SECONDS);
                    while (true) {
                        int n = seq.incrementAndGet();
                        if (n > operations) {
                            return null;
                        }
                        int dice = rnd.nextInt(100);
                        try {
                            if (dice < 20) {
                                Long ent = ents.get(rnd.nextInt(ents.size()));
                                posts.add(postService.publishForEnterprise(ent, "压测企业", null, post("压测帖 " + n)));
                                bump(tally, "发帖");
                            } else if (dice < 30 && !posts.isEmpty()) {
                                Long ent = ents.get(rnd.nextInt(ents.size()));
                                postService.updateForEnterprise(ent, posts.get(rnd.nextInt(posts.size())), post("改过的 " + n));
                                bump(tally, "改帖");
                            } else if (dice < 36 && !posts.isEmpty()) {
                                Long ent = ents.get(rnd.nextInt(ents.size()));
                                postService.deleteForEnterprise(ent, posts.get(rnd.nextInt(posts.size())));
                                bump(tally, "删帖");
                            } else if (dice < 70) {
                                int idx = rnd.nextInt(orders.size());
                                reviews.add(reviewService.create(orderOwners.get(idx), orderEnterprises.get(idx),
                                        create(orders.get(idx), 1 + rnd.nextInt(5), "压测评价 " + n)));
                                bump(tally, "评价");
                            } else if (dice < 82 && !reviews.isEmpty()) {
                                reviewService.hide(reviews.get(rnd.nextInt(reviews.size())), "压测屏蔽", ADMIN);
                                bump(tally, "屏蔽");
                            } else if (dice < 90 && !reviews.isEmpty()) {
                                reviewService.show(reviews.get(rnd.nextInt(reviews.size())), ADMIN);
                                bump(tally, "恢复");
                            } else if (dice < 94 && !reviews.isEmpty()) {
                                reviewService.delete(reviews.get(rnd.nextInt(reviews.size())), ADMIN);
                                bump(tally, "删评价");
                            } else {
                                Long ent = ents.get(rnd.nextInt(ents.size()));
                                reviewService.listPublic(ent, new PageQuery());
                                reviewService.listForEnterprise(ent, new PageQuery());
                                bump(tally, "看列表");
                            }
                        } catch (BusinessException e) {
                            bump(tally, "拒绝:" + e.getMessage());
                        } catch (Throwable e) {
                            unexpected.add(e);
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(180, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(unexpected.isEmpty(), "压测中出现非业务异常：" + unexpected);

        for (int i = 0; i < orders.size(); i++) {
            Integer live = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM enterprise_review WHERE order_id = ? AND is_deleted = 0", Integer.class, orders.get(i));
            assertTrue(live != null && live <= 1, "一张兑换单至多一条没删的评价，单 " + orders.get(i) + " 有 " + live);
        }
        for (Long ent : ents) {
            for (EnterpriseReviewVO vo : reviewService.listPublic(ent, new PageQuery()).getRecords()) {
                assertTrue(reviewService.listForAdmin(ent, 0, new PageQuery()).getRecords().stream()
                        .anyMatch(r -> r.getId().equals(vo.getId())), "公开列表里出现了不是「正常」的评价：" + vo.getId());
            }
        }
        System.out.println("[压测] 爱心企业社区段：" + operations + " 次操作 / " + threads + " 线程 / "
                + (System.currentTimeMillis() - started) + " ms，分布 " + new java.util.TreeMap<>(tally));
    }

    // ================= 造数 =================

    private Long pickedOrder(Long ent, Long buyer) {
        Long goodsId = sponsorService.createGoods(ent, goods("并发评价商品", 3, 200));
        goodsService.submitForSponsor(goodsId, ent);
        goodsService.approve(goodsId, ADMIN);
        SponsorTestSupport.givePoints(jdbc, buyer, 50);
        MallOrder order = orderService.placeOrder(buyer, SponsorTestSupport.specOf(jdbc, goodsId));
        orderService.approve(order.getId(), ADMIN);
        orderService.verify(jdbc.queryForObject("SELECT pickup_code FROM mall_order WHERE id = ?", String.class, order.getId()), ADMIN);
        return order.getId();
    }

    private Long volunteer() {
        return SponsorTestSupport.volunteer(volunteerMapper, cryptoUtil, EnterpriseTestSupport.phone());
    }

    private static void bump(Map<String, AtomicInteger> tally, String key) {
        tally.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    private static SocialDTOs.PostSave post(String content) {
        SocialDTOs.PostSave d = new SocialDTOs.PostSave();
        d.setContent(content);
        return d;
    }

    private static EnterpriseReviewDTOs.Create create(Long orderId, int rating, String content) {
        EnterpriseReviewDTOs.Create d = new EnterpriseReviewDTOs.Create();
        d.setOrderId(orderId);
        d.setRating(rating);
        d.setContent(content);
        return d;
    }
}
