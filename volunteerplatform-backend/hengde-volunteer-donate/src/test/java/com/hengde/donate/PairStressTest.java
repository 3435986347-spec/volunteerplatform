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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hengde.donate.BookDonationTestSupport.ADMIN;
import static com.hengde.donate.BookDonationTestSupport.next;
import static com.hengde.donate.BookDonationTestSupport.phone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结对压力测试（{@code @Tag("stress")}，默认不跑；{@code -Dtest.excludedGroups=none -Dgroups=stress}）。
 *
 * <p>一群人在一小撮项目上随机地登记、撤回、被确认、被取消。不断言「谁成功」，只断言结束时的<b>不变量</b>：
 * ① 每个项目的认捐额 = 该项目下已成立结对的金额之和（<b>一分不多一分不少</b>）；
 * ② 认捐额不越过受助金额；③ 每人每项目至多一条活登记；④ 项目状态与认捐额对得上
 * （已结对 ⇔ 认捐额已达标）；⑤ 除业务拒绝外没有任何异常（死锁、500 都算失败）。</p>
 *
 * @author hengde
 */
@Tag("stress")
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class PairStressTest {

    private static final int PROJECTS = 10;
    private static final int VOLUNTEERS = 30;
    private static final int OPS_PER_VOLUNTEER = 16;
    private static final int THREADS = 12;
    private static final String TARGET = "1000";

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

    @Test
    void randomPairTraffic_keepsPledgedAmountExactlyEqualToEstablishedSum() throws Exception {
        List<Long> projects = new ArrayList<>();
        for (int i = 0; i < PROJECTS; i++) {
            PairDTOs.ProjectSave d = new PairDTOs.ProjectSave();
            d.setTitle("压测结对-" + next() + "-" + i);
            d.setProjectType(PairFlow.TYPE_STUDY);
            d.setTargetAmount(new BigDecimal(TARGET));
            Long id = projectService.create(d, ADMIN);
            projectService.publish(id);
            projects.add(id);
        }
        List<Long> vols = new ArrayList<>();
        for (int i = 0; i < VOLUNTEERS; i++) {
            vols.add(BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "压测结对人" + i, phone(), true));
        }

        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> fs = new ArrayList<>();
        for (Long v : vols) {
            fs.add(pool.submit(() -> {
                start.await();
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int op = 0; op < OPS_PER_VOLUNTEER; op++) {
                    Long projectId = projects.get(rnd.nextInt(projects.size()));
                    int dice = rnd.nextInt(100);
                    String kind = dice < 45 ? "登记" : dice < 60 ? "撤回" : dice < 85 ? "确认" : "取消";
                    try {
                        switch (kind) {
                            case "登记" -> {
                                PairDTOs.Register d = new PairDTOs.Register();
                                d.setAmountType(PairFlow.AMOUNT_PARTIAL);
                                d.setAmount(new BigDecimal(100 * (1 + rnd.nextInt(4))));
                                pairService.register(projectId, v, d);
                            }
                            case "撤回" -> pairService.withdraw(projectId, v);
                            case "确认" -> establishOne(projectId);
                            default -> cancelOne(projectId);
                        }
                        outcomes.computeIfAbsent(kind + "·成功", k -> new AtomicInteger()).incrementAndGet();
                    } catch (BusinessException e) {
                        outcomes.computeIfAbsent(kind + "·拒绝·" + e.getMessage(), k -> new AtomicInteger())
                                .incrementAndGet();
                    } catch (Exception e) {
                        failures.add(kind + " → " + e);
                    }
                }
                return null;
            }));
        }
        long t0 = System.currentTimeMillis();
        start.countDown();
        for (Future<?> f : fs) {
            f.get(5, TimeUnit.MINUTES);
        }
        long wall = System.currentTimeMillis() - t0;
        pool.shutdownNow();

        int total = VOLUNTEERS * OPS_PER_VOLUNTEER;
        StringBuilder report = new StringBuilder("\n==== 结对压测：").append(total).append(" 次操作 / ")
                .append(THREADS).append(" 线程 / ").append(wall).append(" ms（")
                .append(String.format("%.1f", total * 1000.0 / Math.max(1, wall))).append(" ops/s）====\n");
        new TreeMap<>(outcomes).forEach((k, n) -> report.append("  ").append(k).append(" : ").append(n.get()).append('\n'));
        System.out.println(report);

        assertTrue(failures.isEmpty(), "除业务拒绝外不应有任何异常（死锁 / 500）：" + failures);
        assertTrue(outcomes.keySet().stream().anyMatch(k -> k.startsWith("确认·成功")), "压测至少要真的确认成立过");

        for (Long projectId : projects) {
            BigDecimal pledged = jdbc.queryForObject(
                    "SELECT pledged_amount FROM donate_pair_project WHERE id = ?", BigDecimal.class, projectId);
            BigDecimal established = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount), 0) FROM donate_pair_record WHERE project_id = ? AND status = ?",
                    BigDecimal.class, projectId, PairFlow.PAIR_ESTABLISHED);
            assertEquals(0, pledged.compareTo(established),
                    "项目 " + projectId + " 的认捐额与已成立结对之和对不上：" + pledged + " vs " + established);
            assertTrue(pledged.compareTo(new BigDecimal(TARGET)) <= 0,
                    "项目 " + projectId + " 的认捐额越过了受助金额：" + pledged);

            int status = jdbc.queryForObject("SELECT status FROM donate_pair_project WHERE id = ?",
                    Integer.class, projectId);
            boolean full = pledged.compareTo(new BigDecimal(TARGET)) >= 0;
            assertEquals(full ? PairFlow.PROJECT_PAIRED : PairFlow.PROJECT_OPEN, status,
                    "项目 " + projectId + " 的状态与认捐额对不上：认捐 " + pledged + "，状态 " + status);

            Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT volunteer_id FROM donate_pair_record "
                    + "WHERE project_id = ? AND is_deleted = 0 AND status IN (0, 1) "
                    + "GROUP BY volunteer_id HAVING COUNT(*) > 1) t", Integer.class, projectId);
            assertEquals(0, dup, "项目 " + projectId + " 有人同时挂着两条活登记");
        }
    }

    /** 挑该项目下一条待确认的登记去确认（没有就跳过——这在随机流量里是正常的）。 */
    private void establishOne(Long projectId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM donate_pair_record WHERE project_id = ? AND status = ? "
                + "ORDER BY id LIMIT 1", Long.class, projectId, PairFlow.PAIR_REGISTERED);
        if (ids.isEmpty()) {
            throw new BusinessException("没有待确认的登记");
        }
        pairService.establish(ids.get(0), ADMIN);
    }

    /** 挑该项目下一条已成立的登记去取消。 */
    private void cancelOne(Long projectId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM donate_pair_record WHERE project_id = ? AND status = ? "
                + "ORDER BY id LIMIT 1", Long.class, projectId, PairFlow.PAIR_ESTABLISHED);
        if (ids.isEmpty()) {
            throw new BusinessException("没有已成立的结对");
        }
        pairService.cancel(ids.get(0), "压测取消", ADMIN);
    }
}
