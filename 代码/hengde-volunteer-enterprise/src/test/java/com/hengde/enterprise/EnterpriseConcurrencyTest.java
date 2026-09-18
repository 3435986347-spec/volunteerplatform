package com.hengde.enterprise;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.sms.SmsScene;
import com.hengde.common.sms.VerifyCodeService;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.common.utils.RedisUtil;
import com.hengde.enterprise.dto.EnterpriseDTOs;
import com.hengde.enterprise.service.EnterpriseAccountService;
import com.hengde.enterprise.service.EnterpriseAdminService;
import com.hengde.enterprise.service.EnterpriseAuthService;
import org.junit.jupiter.api.Tag;
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
import static com.hengde.enterprise.EnterpriseTestSupport.creditCode;
import static com.hengde.enterprise.EnterpriseTestSupport.phone;
import static com.hengde.enterprise.EnterpriseTestSupport.register;
import static com.hengde.enterprise.EnterpriseTestSupport.username;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 爱心企业账号的并发（前三条<b>必跑</b>）与压测（{@code @Tag("stress")}）。
 *
 * <p>① 8 个人同时用同一个登录账号注册：恰好一个成功，其余都是「已经被注册」（唯一键兜底，不是先查挡住的）。
 * ② 通过与驳回同时点：只成一个，输家的报错说的是赢家之后的状态。
 * ③ <b>改身份信息等在途的审核通过、并看到它</b>：裸 JDBC 事务先把状态改成正常不提交，改名必须被挡住，提交后被拒——
 * 「只在待审核 / 驳回时能改」写在 UPDATE 的 WHERE 里才有这个效果，方法开头读一次状态再改是挡不住的。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class EnterpriseConcurrencyTest {

    @Autowired
    private EnterpriseAuthService authService;
    @Autowired
    private EnterpriseAccountService accountService;
    @Autowired
    private EnterpriseAdminService adminService;
    @Autowired
    private VerifyCodeService verifyCodeService;
    @Autowired
    private RedisUtil redisUtil;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;

    private EnterpriseDTOs.Register ready(String user) {
        String p = phone();
        return register(user, creditCode(), p, EnterpriseTestSupport.sendAndReadCode(verifyCodeService, redisUtil, p, SmsScene.ENTERPRISE_REGISTER));
    }

    @Test
    void eightRegistrationsWithTheSameUsername_oneAccount() throws Exception {
        String user = username("race_");
        int n = 8;
        List<EnterpriseDTOs.Register> forms = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            forms.add(ready(user));
        }
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<Long>> fs = new ArrayList<>();
        for (EnterpriseDTOs.Register f : forms) {
            fs.add(pool.submit(() -> {
                barrier.await();
                return authService.register(f);
            }));
        }
        int ok = 0;
        for (Future<Long> f : fs) {
            try {
                f.get(30, TimeUnit.SECONDS);
                ok++;
            } catch (ExecutionException e) {
                assertInstanceOf(BusinessException.class, e.getCause(), String.valueOf(e.getCause()));
                assertTrue(e.getCause().getMessage().contains("登录账号已经被注册"), e.getCause().getMessage());
            }
        }
        pool.shutdownNow();
        assertEquals(1, ok);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM enterprise_account WHERE username = ? AND is_deleted = 0", Integer.class, user));
    }

    @Test
    void approveAndRejectAtOnce_oneWins_loserSeesTheWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 12; round++) {
                Long id = authService.register(ready(username("ar_")));
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> approve = pool.submit(() -> {
                    barrier.await();
                    adminService.approve(id, ADMIN);
                    return null;
                });
                Future<?> reject = pool.submit(() -> {
                    barrier.await();
                    adminService.reject(id, "资料不全", ADMIN + 1);
                    return null;
                });
                String loser = null;
                int wins = 0;
                for (Future<?> f : List.of(approve, reject)) {
                    try {
                        f.get(30, TimeUnit.SECONDS);
                        wins++;
                    } catch (ExecutionException e) {
                        assertInstanceOf(BusinessException.class, e.getCause(), String.valueOf(e.getCause()));
                        loser = e.getCause().getMessage();
                    }
                }
                assertEquals(1, wins, "通过与驳回只成一个");
                Integer status = jdbc.queryForObject("SELECT status FROM enterprise_account WHERE id = ?", Integer.class, id);
                assertTrue(loser != null && loser.contains(status == 1 ? "当前：正常" : "当前：已驳回"), "输家说的是赢家之后的状态：" + loser);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void identityChangeWaitsForAnInFlightApproval_andIsRefused() throws Exception {
        Long id = authService.register(ready(username("idc_")));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection approver = dataSource.getConnection()) {
            approver.setAutoCommit(false);
            try (PreparedStatement ps = approver.prepareStatement("UPDATE enterprise_account SET status = 1 WHERE id = ?")) {
                ps.setLong(1, id);
                assertEquals(1, ps.executeUpdate());
            }
            EnterpriseDTOs.Profile rename = new EnterpriseDTOs.Profile();
            rename.setName("审核那一刻改的名");
            Future<?> renaming = pool.submit(() -> accountService.updateProfile(id, rename));
            assertThrows(TimeoutException.class, () -> renaming.get(1500, TimeUnit.MILLISECONDS), "改名应当在等审核提交");
            approver.commit();
            ExecutionException e = assertThrows(ExecutionException.class, () -> renaming.get(10, TimeUnit.SECONDS));
            assertInstanceOf(BusinessException.class, e.getCause());
            assertTrue(e.getCause().getMessage().contains("审核通过之后企业名称"), e.getCause().getMessage());
        } finally {
            pool.shutdownNow();
        }
        assertTrue(!"审核那一刻改的名".equals(jdbc.queryForObject("SELECT name FROM enterprise_account WHERE id = ?", String.class, id)));
    }

    /**
     * 压测：40 份注册（其中 8 份抢 2 个登录账号）+ 4 个管理员乱序通过 / 驳回 / 暂停 / 恢复 / 删除 + 企业改资料 / 重新提交。
     * 断言：未删除的账号里登录账号与信用代码不重复；正常的都有审核人、驳回的都有原因、暂停的都有暂停原因；零非业务异常。
     */
    @Tag("stress")
    @Test
    void enterpriseChurn_invariantsHold() throws Exception {
        List<EnterpriseDTOs.Register> forms = new ArrayList<>();
        String shared1 = username("dup_");
        String shared2 = username("dup_");
        for (int i = 0; i < 40; i++) {
            forms.add(ready(i < 4 ? shared1 : i < 8 ? shared2 : username("st_")));
        }
        List<Long> ids = new CopyOnWriteArrayList<>();
        Map<String, AtomicInteger> tally = new ConcurrentHashMap<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        AtomicInteger formIdx = new AtomicInteger();
        AtomicInteger seq = new AtomicInteger();
        int ops = 600;
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long start = System.currentTimeMillis();
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(pool.submit(() -> {
                Random rnd = new Random();
                while (seq.getAndIncrement() < ops) {
                    int kind = rnd.nextInt(100);
                    String label = "空转";
                    try {
                        int fi = formIdx.get();
                        if (kind < 15 && fi < forms.size() && formIdx.compareAndSet(fi, fi + 1)) {
                            label = "注册";
                            ids.add(authService.register(forms.get(fi)));
                        } else if (ids.isEmpty()) {
                            label = "空转";
                        } else {
                            Long id = ids.get(rnd.nextInt(ids.size()));
                            long admin = ADMIN + rnd.nextInt(4);
                            if (kind < 35) {
                                label = "通过";
                                adminService.approve(id, admin);
                            } else if (kind < 45) {
                                label = "驳回";
                                adminService.reject(id, "资料要补", admin);
                            } else if (kind < 57) {
                                label = "暂停";
                                adminService.pause(id, "压测暂停", admin);
                            } else if (kind < 69) {
                                label = "恢复";
                                adminService.resume(id, admin);
                            } else if (kind < 72) {
                                label = "删除";
                                adminService.delete(id, admin);
                            } else if (kind < 84) {
                                label = "改资料";
                                EnterpriseDTOs.Profile p = new EnterpriseDTOs.Profile();
                                p.setName(rnd.nextBoolean() ? "压测改名" + rnd.nextInt(1000) : null);
                                p.setIntro("压测介绍" + rnd.nextInt(1000));
                                accountService.updateProfile(id, p);
                            } else if (kind < 92) {
                                label = "重新提交";
                                accountService.resubmit(id);
                            } else {
                                label = "看列表";
                                adminService.list(new com.hengde.common.page.PageQuery(), rnd.nextInt(4), null);
                            }
                        }
                        tally.computeIfAbsent(label + "·成功", k -> new AtomicInteger()).incrementAndGet();
                    } catch (BusinessException e) {
                        tally.computeIfAbsent(label + "·拒绝·" + e.getMessage().replaceAll("（当前：.*）", "（当前：…）"),
                                k -> new AtomicInteger()).incrementAndGet();
                    } catch (Throwable e) {
                        unexpected.add(e);
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : fs) {
            f.get(3, TimeUnit.MINUTES);
        }
        pool.shutdownNow();
        System.out.println("==== 爱心企业账号压测：" + ops + " 次 / " + threads + " 线程 / " + (System.currentTimeMillis() - start)
                + " ms ====\n  " + new TreeMap<>(tally));
        assertTrue(unexpected.isEmpty(), "不应有非业务异常：" + unexpected.stream().limit(3).map(String::valueOf).toList());
        for (String shared : List.of(shared1, shared2)) {
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM enterprise_account WHERE username = ? AND is_deleted = 0", Integer.class, shared) <= 1,
                    "抢同一个登录账号的几份，未删除的至多一份");
        }
        assertEquals(tally.getOrDefault("注册·成功", new AtomicInteger()).get(), ids.size());
        assertEquals(ids.size(), jdbc.queryForObject("SELECT COUNT(*) FROM enterprise_account WHERE id IN ("
                + (ids.isEmpty() ? "0" : String.join(",", ids.stream().map(String::valueOf).toList())) + ")", Integer.class),
                "报成功的注册一一落库");
        String in = ids.isEmpty() ? "0" : String.join(",", ids.stream().map(String::valueOf).toList());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM enterprise_account WHERE id IN (" + in + ") AND is_deleted = 0 AND ("
                + "(status = 1 AND audit_by IS NULL) OR (status = 2 AND reject_reason IS NULL) OR (status = 3 AND pause_reason IS NULL))", Integer.class),
                "正常的有审核人、驳回的有原因、暂停的有暂停原因");
        assertTrue(tally.getOrDefault("通过·成功", new AtomicInteger()).get() > 0 && tally.getOrDefault("暂停·成功", new AtomicInteger()).get() > 0,
                String.valueOf(tally));
    }
}
