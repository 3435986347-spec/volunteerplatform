package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.PairFlow;
import com.hengde.donate.dto.PairDTOs;
import com.hengde.donate.service.PairProjectService;
import com.hengde.donate.service.PairService;
import com.hengde.donate.vo.PairVOs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.hengde.donate.BookDonationTestSupport.ADMIN;
import static com.hengde.donate.BookDonationTestSupport.next;
import static com.hengde.donate.BookDonationTestSupport.phone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结对登记的并发（V3 结对批）。
 *
 * <p>每一轮都用 {@code Future.get()} 收下全部结果并断言<b>不变量</b>——只看最终状态的用例，
 * 在「两笔都确认成功、认捐额越过受助金额」这种缺陷上照样是绿的。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class PairConcurrencyTest {

    @Autowired
    private PairProjectService projectService;
    @Autowired
    private PairService pairService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;

    /** 同一个人连点「登记」：生成列唯一键只让一条活登记落库。 */
    @Test
    void doubleClickRegister_leavesExactlyOneActiveRecord() throws Exception {
        Long projectId = openProject("连点登记-" + next(), "1000");
        Long donor = volunteer("连点的人");
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tasks.add(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    pairService.register(projectId, donor, register("100"));
                    return "OK";
                } catch (BusinessException e) {
                    return e.getMessage();
                }
            });
        }
        List<String> results = runAll(tasks);
        assertEquals(1, results.stream().filter("OK"::equals).count(), results.toString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM donate_pair_record WHERE project_id = ? "
                + "AND volunteer_id = ? AND status IN (0, 1)", Integer.class, projectId, donor));
    }

    /**
     * 两笔<b>各自都装得下、加起来装不下</b>的登记同时被确认：只能成立一笔。
     *
     * <p>金额刻意取 600 + 600（目标 1000）而不是 1000 + 1000：后者第一笔一确认项目就满额转「已结对」，
     * 第二笔会被<b>状态</b>条件挡下，于是这条用例即使删掉金额上限也照样绿——
     * 变异验证当场证过（去掉上限条件，用例仍通过）。600 + 600 时项目在第一笔之后仍是「进行中」，
     * 挡住第二笔的只可能是累加语句里的上限（{@code pledged_amount + ? <= target_amount}）。</p>
     */
    @Test
    void concurrentEstablish_neverPledgesBeyondTheTarget() throws Exception {
        for (int round = 0; round < 8; round++) {
            Long projectId = openProject("同时确认-" + next(), "1000");
            Long a = volunteer("半额甲" + round);
            Long b = volunteer("半额乙" + round);
            PairVOs.PairRecord ra = pairService.register(projectId, a, register("600"));
            PairVOs.PairRecord rb = pairService.register(projectId, b, register("600"));

            CyclicBarrier barrier = new CyclicBarrier(2);
            List<String> results = runAll(List.of(
                    establishTask(barrier, ra.getId()),
                    establishTask(barrier, rb.getId())));

            assertEquals(1, results.stream().filter("OK"::equals).count(),
                    "第 " + round + " 轮：600 + 600 超过目标 1000，只能成立一笔：" + results);
            assertTrue(results.stream().filter(r -> !"OK".equals(r))
                    .allMatch(r -> r.contains("剩余缺口") || r.contains("不在进行中")), results.toString());
            BigDecimal pledged = jdbc.queryForObject(
                    "SELECT pledged_amount FROM donate_pair_project WHERE id = ?", BigDecimal.class, projectId);
            assertEquals(0, pledged.compareTo(new BigDecimal("600")),
                    "认捐额应恰好是成立的那一笔，不能两笔都加上去，实得 " + pledged);
            assertTrue(pledged.compareTo(new BigDecimal("1000")) <= 0, "认捐额越过了受助金额：" + pledged);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM donate_pair_record WHERE project_id = ? "
                    + "AND status = ?", Integer.class, projectId, PairFlow.PAIR_ESTABLISHED));
        }
    }

    /**
     * 本人撤回与协会确认赛跑：<b>必然恰好一个成功</b>。
     *
     * <p>都成功的话，会出现一条「已取消却已成立」的登记——认捐额加了、证书也发了，而本人以为自己撤掉了。</p>
     */
    @Test
    void withdrawRacingEstablish_exactlyOneWins() throws Exception {
        for (int round = 0; round < 12; round++) {
            Long projectId = openProject("撤回对确认-" + next(), "1000");
            Long donor = volunteer("边撤边确认" + round);
            PairVOs.PairRecord r = pairService.register(projectId, donor, register("300"));
            CyclicBarrier barrier = new CyclicBarrier(2);

            List<String> results = runAll(List.of(
                    () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        try {
                            pairService.withdraw(projectId, donor);
                            return "WITHDRAW_OK";
                        } catch (BusinessException e) {
                            return "WITHDRAW_REJECTED:" + e.getMessage();
                        }
                    },
                    () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        try {
                            pairService.establish(r.getId(), ADMIN);
                            return "ESTABLISH_OK";
                        } catch (BusinessException e) {
                            return "ESTABLISH_REJECTED:" + e.getMessage();
                        }
                    }));
            assertEquals(1, results.stream().filter(s -> s.endsWith("_OK")).count(),
                    "第 " + round + " 轮：撤回与确认必然恰好一个成功：" + results);

            int status = jdbc.queryForObject("SELECT status FROM donate_pair_record WHERE id = ?",
                    Integer.class, r.getId());
            BigDecimal pledged = jdbc.queryForObject(
                    "SELECT pledged_amount FROM donate_pair_project WHERE id = ?", BigDecimal.class, projectId);
            if (status == PairFlow.PAIR_ESTABLISHED) {
                assertEquals(0, pledged.compareTo(new BigDecimal("300")), "成立了就该记上这笔认捐额");
            } else {
                assertEquals(PairFlow.PAIR_CANCELLED, status);
                assertEquals(0, pledged.compareTo(BigDecimal.ZERO), "撤回了就不该有认捐额：" + results);
            }
        }
    }

    // ---------- helpers ----------

    private Callable<String> establishTask(CyclicBarrier barrier, Long recordId) {
        return () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
                pairService.establish(recordId, ADMIN);
                return "OK";
            } catch (BusinessException e) {
                return e.getMessage();
            }
        };
    }

    private Long openProject(String title, String target) {
        PairDTOs.ProjectSave d = new PairDTOs.ProjectSave();
        d.setTitle(title);
        d.setProjectType(PairFlow.TYPE_STUDY);
        d.setTargetAmount(new BigDecimal(target));
        Long id = projectService.create(d, ADMIN);
        projectService.publish(id);
        return id;
    }

    private static PairDTOs.Register register(String amount) {
        PairDTOs.Register d = new PairDTOs.Register();
        d.setAmountType(PairFlow.AMOUNT_PARTIAL);
        d.setAmount(new BigDecimal(amount));
        return d;
    }

    private Long volunteer(String name) {
        return BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, name, phone(), true);
    }

    private static List<String> runAll(List<Callable<String>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<String>> fs = new ArrayList<>();
            for (Callable<String> t : tasks) {
                fs.add(pool.submit(t));
            }
            List<String> out = new ArrayList<>();
            for (Future<String> f : fs) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
