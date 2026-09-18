package com.hengde.organization.exam;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.exam.service.ExamAttemptService;
import com.hengde.organization.exam.service.ExamPaperService;
import com.hengde.organization.exam.service.TempLeaderQueryService;
import com.hengde.organization.exam.service.TempLeaderService;
import com.hengde.organization.exam.support.ExamCodes;
import com.hengde.organization.exam.vo.ExamVOs;
import com.hengde.organization.form.dto.FormDTOs;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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

import static com.hengde.organization.exam.ExamTestSupport.ADMIN;
import static com.hengde.organization.exam.ExamTestSupport.GRADER;
import static com.hengde.organization.exam.ExamTestSupport.a;
import static com.hengde.organization.exam.ExamTestSupport.grade;
import static com.hengde.organization.exam.ExamTestSupport.score;
import static com.hengde.organization.exam.ExamTestSupport.submit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 考试的并发（前五条<b>必跑</b>）与压测（{@code @Tag("stress")}）。
 *
 * <p>① <b>这个人的交卷 / 阅卷 / 撤销都在等同一把锁</b>——由测试线程直接持住，断言三个写操作都被挡住、放开后照常完成。
 * 「阅卷刚授予资格、同一个人又交进一份新卷」这个窗口靠调度撞不稳，这条才是那把锁的证据。</p>
 * <p>② <b>交卷等在途的停止</b>：裸 JDBC 事务先把试卷改成已停止但不提交，交卷必须被挡住，提交后它看到的是已停止。</p>
 * <p>③ 同一个人 8 线程同时交一份有主观题的卷：恰好一份待阅卷。④ 4 个阅卷人同时阅同一份：只出一次分、只授一条资格。
 * ⑤ 16 人同时及格：每人一条资格、零死锁（授予资格不能按人范围 UPDATE，见 {@code OrgTempLeaderQualificationMapper.closeExpiredById}）。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class ExamConcurrencyTest {

    @Autowired
    private ExamPaperService paperService;
    @Autowired
    private ExamAttemptService attemptService;
    @Autowired
    private TempLeaderService tempLeaderService;
    @Autowired
    private TempLeaderQueryService queryService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void submitGradeAndRevokeAllWaitForTheVolunteerLock() throws Exception {
        ExamTestSupport.closeAllOpen(jdbc);
        Long paperId = paperService.create(ExamTestSupport.mixedPaper(10, null), ADMIN);
        paperService.publish(paperId);
        List<ExamVOs.Question> qs = paperService.detail(paperId).getQuestions();
        Long fill = qs.get(2).getId(), text = qs.get(3).getId();

        Long grading = ExamTestSupport.volunteer(volunteerMapper, true);
        Long attemptId = attemptService.submit(grading, submit(paperId, List.of(a(fill, "120")))).getId();
        Long revoking = ExamTestSupport.volunteer(volunteerMapper, true);
        Long toRevoke = attemptService.submit(revoking, submit(paperId, List.of(a(fill, "120")))).getId();
        attemptService.grade(toRevoke, grade(List.of(score(fill, 20), score(text, 0)), null), GRADER);
        Long qualificationId = queryService.current(revoking).getId();
        Long submitting = ExamTestSupport.volunteer(volunteerMapper, true);

        List<RLock> held = new ArrayList<>();
        for (Long v : List.of(grading, revoking, submitting)) {
            RLock lock = redissonClient.getLock(ExamCodes.LOCK_PREFIX + v);
            lock.lock();
            held.add(lock);
        }
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> g = pool.submit(() -> attemptService.grade(attemptId, grade(List.of(score(fill, 20), score(text, 0)), null), GRADER));
            Future<?> r = pool.submit(() -> tempLeaderService.revoke(qualificationId, "评价过低", ADMIN));
            Future<?> s = pool.submit(() -> attemptService.submit(submitting, submit(paperId, List.of(a(fill, "120")))));
            assertThrows(TimeoutException.class, () -> g.get(1500, TimeUnit.MILLISECONDS), "阅卷应当在等这个人的锁");
            assertThrows(TimeoutException.class, () -> r.get(200, TimeUnit.MILLISECONDS), "撤销应当在等这个人的锁");
            assertThrows(TimeoutException.class, () -> s.get(200, TimeUnit.MILLISECONDS), "交卷应当在等这个人的锁");
            held.forEach(RLock::unlock);
            held.clear();
            g.get(10, TimeUnit.SECONDS);
            r.get(10, TimeUnit.SECONDS);
            s.get(10, TimeUnit.SECONDS);
        } finally {
            held.forEach(l -> {
                if (l.isHeldByCurrentThread()) {
                    l.unlock();
                }
            });
            pool.shutdownNow();
        }
        assertTrue(queryService.isTempLeader(grading));
        assertTrue(!queryService.isTempLeader(revoking));
        assertEquals(1, attemptService.list(new com.hengde.common.page.PageQuery(), 1, paperId, submitting, null).getRecords().size());
    }

    @Test
    void submitWaitsForAnInFlightClose_andThenSeesItClosed() throws Exception {
        ExamTestSupport.closeAllOpen(jdbc);
        Long paperId = paperService.create(ExamTestSupport.objectivePaper(60, null), ADMIN);
        paperService.publish(paperId);
        Long me = ExamTestSupport.volunteer(volunteerMapper, true);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection closer = dataSource.getConnection()) {
            closer.setAutoCommit(false);
            try (PreparedStatement ps = closer.prepareStatement("UPDATE org_exam_paper SET status = 2 WHERE id = ?")) {
                ps.setLong(1, paperId);
                assertEquals(1, ps.executeUpdate());
            }
            Future<ExamVOs.Attempt> submitting = pool.submit(() -> attemptService.submit(me, submit(paperId, List.of())));
            assertThrows(TimeoutException.class, () -> submitting.get(1500, TimeUnit.MILLISECONDS), "交卷应当在等停止提交");
            closer.commit();
            ExecutionException e = assertThrows(ExecutionException.class, () -> submitting.get(10, TimeUnit.SECONDS));
            assertInstanceOf(BusinessException.class, e.getCause());
            assertTrue(e.getCause().getMessage().contains("已经停止考试"), e.getCause().getMessage());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameVolunteerSubmitsEightTimesAtOnce_onePendingAttempt() throws Exception {
        ExamTestSupport.closeAllOpen(jdbc);
        Long paperId = paperService.create(ExamTestSupport.mixedPaper(60, null), ADMIN);
        paperService.publish(paperId);
        Long me = ExamTestSupport.volunteer(volunteerMapper, true);
        int n = 8;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<ExamVOs.Attempt>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                barrier.await();
                return attemptService.submit(me, submit(paperId, List.of()));
            }));
        }
        int ok = 0;
        for (Future<ExamVOs.Attempt> f : fs) {
            try {
                f.get(30, TimeUnit.SECONDS);
                ok++;
            } catch (ExecutionException e) {
                assertInstanceOf(BusinessException.class, e.getCause(), String.valueOf(e.getCause()));
                assertTrue(e.getCause().getMessage().contains("正在阅卷"), e.getCause().getMessage());
            }
        }
        pool.shutdownNow();
        assertEquals(1, ok);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM org_exam_attempt WHERE volunteer_id = ?", Integer.class, me));
    }

    @Test
    void fourGradersAtOnce_oneScore_oneQualification() throws Exception {
        ExamTestSupport.closeAllOpen(jdbc);
        Long paperId = paperService.create(ExamTestSupport.mixedPaper(10, null), ADMIN);
        paperService.publish(paperId);
        List<ExamVOs.Question> qs = paperService.detail(paperId).getQuestions();
        Long fill = qs.get(2).getId(), text = qs.get(3).getId();
        Long me = ExamTestSupport.volunteer(volunteerMapper, true);
        Long attemptId = attemptService.submit(me, submit(paperId, List.of(a(fill, "120")))).getId();
        int n = 4;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<ExamVOs.Attempt>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int s = 10 + i;
            fs.add(pool.submit(() -> {
                barrier.await();
                return attemptService.grade(attemptId, grade(List.of(score(fill, 20), score(text, s)), null), GRADER + s);
            }));
        }
        int ok = 0;
        for (Future<ExamVOs.Attempt> f : fs) {
            try {
                f.get(30, TimeUnit.SECONDS);
                ok++;
            } catch (ExecutionException e) {
                assertInstanceOf(BusinessException.class, e.getCause(), String.valueOf(e.getCause()));
                assertTrue(e.getCause().getMessage().contains("已经出分了"), e.getCause().getMessage());
            }
        }
        pool.shutdownNow();
        assertEquals(1, ok);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM org_temp_leader_qualification WHERE volunteer_id = ?", Integer.class, me));
    }

    /**
     * 16 个人同时交一份全对的客观题试卷：每人恰好一条资格、零死锁。授予资格时「收尾到期行」若按人范围 UPDATE，
     * 还没有资格行的相邻 id 会共持同一段间隙锁、再互等插入意向锁——这条用例与压测都会撞出 {@code Deadlock found}。
     */
    @Test
    void sixteenPeoplePassAtOnce_eachExactlyOneQualification() throws Exception {
        ExamTestSupport.closeAllOpen(jdbc);
        Long paperId = paperService.create(ExamTestSupport.objectivePaper(60, 12), ADMIN);
        paperService.publish(paperId);
        List<ExamVOs.Question> qs = paperService.detail(paperId).getQuestions();
        List<FormDTOs.Answer> right = List.of(a(qs.get(0).getId(), "B"), a(qs.get(1).getId(), List.of("A", "C")),
                a(qs.get(2).getId(), false));
        int rounds = 3;
        int n = 16;
        for (int round = 0; round < rounds; round++) {
            List<Long> people = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                people.add(ExamTestSupport.volunteer(volunteerMapper, true));
            }
            CyclicBarrier barrier = new CyclicBarrier(n);
            ExecutorService pool = Executors.newFixedThreadPool(n);
            List<Future<ExamVOs.Attempt>> fs = new ArrayList<>();
            for (Long v : people) {
                fs.add(pool.submit(() -> {
                    barrier.await();
                    return attemptService.submit(v, submit(paperId, right));
                }));
            }
            for (Future<ExamVOs.Attempt> f : fs) {
                assertTrue(f.get(30, TimeUnit.SECONDS).getPassed());
            }
            pool.shutdownNow();
            for (Long v : people) {
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM org_temp_leader_qualification WHERE volunteer_id = ?",
                        Integer.class, v));
            }
        }
    }

    /**
     * 压测：60 人 × 两份轮换开放的试卷，乱序交卷（对错随机）/ 阅卷（分数随机）/ 撤销 / 看我的考试 / 看阅卷队列 / 停止并换卷。
     *
     * <p>断言：每人至多一份待阅卷、至多一条没撤销的资格；<b>资格条数＝及格的答卷数</b>且每条资格都挂在一份及格的答卷上；
     * 没有人同时持有待阅卷的答卷和有效资格；出分的答卷总分＝客观＋主观、及格＝总分达线；零非业务异常。</p>
     */
    @Tag("stress")
    @Test
    void examChurn_invariantsHold() throws Exception {
        ExamTestSupport.closeAllOpen(jdbc);
        List<Long> papers = new CopyOnWriteArrayList<>();
        papers.add(paperService.create(ExamTestSupport.mixedPaper(60, null), ADMIN));
        papers.add(paperService.create(ExamTestSupport.objectivePaper(60, 12), ADMIN));
        paperService.publish(papers.get(0));
        Map<Long, List<ExamVOs.Question>> questions = new ConcurrentHashMap<>();
        for (Long p : papers) {
            questions.put(p, paperService.detail(p).getQuestions());
        }
        List<Long> people = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            people.add(ExamTestSupport.volunteer(volunteerMapper, true));
        }
        int ops = 1200;
        int threads = 16;
        Map<String, AtomicInteger> tally = new ConcurrentHashMap<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        AtomicInteger seq = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long start = System.currentTimeMillis();
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(pool.submit(() -> {
                Random rnd = new Random();
                int i;
                while ((i = seq.getAndIncrement()) < ops) {
                    int kind = rnd.nextInt(100);
                    Long who = people.get(rnd.nextInt(people.size()));
                    String label;
                    try {
                        if (kind < 45) {
                            label = "交卷";
                            ExamVOs.MyExam mine = attemptService.myExam(who);
                            Long paperId = mine.getPaper() == null ? papers.get(rnd.nextInt(2)) : mine.getPaper().getId();
                            attemptService.submit(who, submit(paperId, answers(questions.get(paperId), rnd)));
                        } else if (kind < 72) {
                            label = "阅卷";
                            List<ExamVOs.Attempt> queue = attemptService.list(new com.hengde.common.page.PageQuery(), 1, null, null, null)
                                    .getRecords().stream().filter(x -> questions.containsKey(x.getPaperId())).toList();
                            if (queue.isEmpty()) {
                                throw new BusinessException("没有待阅卷的");
                            }
                            ExamVOs.Attempt pick = queue.get(rnd.nextInt(queue.size()));
                            List<ExamVOs.Question> qs = questions.get(pick.getPaperId());
                            attemptService.grade(pick.getId(), grade(List.of(score(qs.get(2).getId(), rnd.nextInt(21)),
                                    score(qs.get(3).getId(), rnd.nextInt(41))), null), GRADER);
                        } else if (kind < 88) {
                            label = "撤销";
                            var q = queryService.current(who);
                            if (q == null) {
                                throw new BusinessException("不是临时负责人");
                            }
                            tempLeaderService.revoke(q.getId(), "压测撤销", ADMIN);
                        } else if (kind < 99) {
                            label = "看我的考试";
                            attemptService.myExam(who);
                        } else {
                            label = "换卷";
                            // 换卷本身在用例里串行（papers 是用例自己的簿记）；与交卷 / 阅卷 / 撤销照样并发
                            synchronized (papers) {
                                int fromIdx = paperService.openPaper() != null && paperService.openPaper().getId().equals(papers.get(1)) ? 1 : 0;
                                paperService.close(papers.get(fromIdx));
                                Long copy = paperService.copy(papers.get(1 - fromIdx), ADMIN);
                                questions.put(copy, paperService.detail(copy).getQuestions());
                                papers.set(1 - fromIdx, copy);
                                paperService.publish(copy);
                            }
                        }
                        tally.computeIfAbsent(label + "·成功", k -> new AtomicInteger()).incrementAndGet();
                    } catch (BusinessException e) {
                        String msg = e.getMessage().replaceAll("\\d{4}-\\d{2}-\\d{2}", "…").replaceAll("第 \\d+ 题", "第…题");
                        tally.computeIfAbsent("拒绝·" + msg, k -> new AtomicInteger()).incrementAndGet();
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
        System.out.println("==== 临时负责人考试压测：" + ops + " 次 / " + threads + " 线程 / "
                + (System.currentTimeMillis() - start) + " ms ====\n  " + new java.util.TreeMap<>(tally));
        assertTrue(unexpected.isEmpty(), "不应有非业务异常：" + unexpected.stream().limit(3).map(String::valueOf).toList());

        String in = String.join(",", people.stream().map(String::valueOf).toList());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT volunteer_id FROM org_exam_attempt WHERE status = 1 AND volunteer_id IN ("
                + in + ") GROUP BY volunteer_id HAVING COUNT(*) > 1) t", Integer.class), "每人至多一份待阅卷");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT volunteer_id FROM org_temp_leader_qualification WHERE revoked_time IS NULL AND volunteer_id IN ("
                + in + ") GROUP BY volunteer_id HAVING COUNT(*) > 1) t", Integer.class), "每人至多一条没撤销的资格");
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM org_exam_attempt WHERE passed = 1 AND volunteer_id IN (" + in + ")", Integer.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM org_temp_leader_qualification q JOIN org_exam_attempt a ON a.id = q.attempt_id "
                        + "WHERE a.passed = 1 AND q.volunteer_id = a.volunteer_id AND q.volunteer_id IN (" + in + ")", Integer.class),
                "资格条数＝及格的答卷数，且每条资格都挂在这个人一份及格的答卷上");
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM org_temp_leader_qualification WHERE volunteer_id IN (" + in + ")", Integer.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM org_exam_attempt WHERE passed = 1 AND volunteer_id IN (" + in + ")", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM org_exam_attempt a WHERE a.status = 1 AND a.volunteer_id IN (" + in + ") AND EXISTS ("
                + "SELECT 1 FROM org_temp_leader_qualification q WHERE q.volunteer_id = a.volunteer_id AND q.revoked_time IS NULL "
                + "AND (q.expire_time IS NULL OR q.expire_time > NOW()))", Integer.class), "没有人同时持有待阅卷的答卷和有效资格");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM org_exam_attempt a JOIN org_exam_paper p ON p.id = a.paper_id "
                + "WHERE a.status = 2 AND a.volunteer_id IN (" + in + ") AND (a.total_score <> a.objective_score + a.subjective_score "
                + "OR a.passed <> (a.total_score >= p.pass_score))", Integer.class), "总分＝客观＋主观、及格＝总分达线");
        assertTrue(tally.getOrDefault("交卷·成功", new AtomicInteger()).get() > 0 && tally.getOrDefault("阅卷·成功", new AtomicInteger()).get() > 0,
                "交卷与阅卷都真的发生过：" + tally);
    }

    private static List<FormDTOs.Answer> answers(List<ExamVOs.Question> qs, Random rnd) {
        List<FormDTOs.Answer> out = new ArrayList<>();
        for (ExamVOs.Question q : qs) {
            if (rnd.nextInt(5) == 0) {
                continue;
            }
            Object v = switch (q.getType()) {
                case 1 -> rnd.nextInt(3) == 0 ? "A" : "B";
                case 2 -> rnd.nextBoolean() ? List.of("A", "C") : List.of("B");
                case 3 -> q.getSort() == 2 ? rnd.nextInt(4) != 0 : rnd.nextInt(4) == 0;
                default -> "答案" + rnd.nextInt(100);
            };
            out.add(a(q.getId(), v));
        }
        return out;
    }
}
