package com.hengde.organization.form;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.form.dto.FormDTOs;
import com.hengde.organization.form.service.FormService;
import com.hengde.organization.form.service.FormSubmissionService;
import com.hengde.organization.form.support.QuestionType;
import com.hengde.organization.form.vo.FormVOs;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hengde.organization.form.FormTestSupport.ADMIN;
import static com.hengde.organization.form.FormTestSupport.a;
import static com.hengde.organization.form.FormTestSupport.next;
import static com.hengde.organization.form.FormTestSupport.q;
import static com.hengde.organization.form.FormTestSupport.submit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 问卷压力测试（{@code @Tag("stress")}，默认不跑；{@code -Dtest.excludedGroups=none -Dgroups=stress}）。
 *
 * <p>120 人、每人 3 次连点交一份「每人一次」的问卷，另有 60 次交「不限次数」的问卷，
 * 中途有人把后一份停掉，再夹杂坏答卷。断言：每人在「每人一次」那份上恰好一份；
 * 被停掉的那份，成功次数与库里答卷数一致；除业务拒绝外零异常（死锁、连接池超时都算失败）。</p>
 *
 * @author hengde
 */
@Tag("stress")
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class FormStressTest {

    private static final int THREADS = 16;

    @Autowired
    private FormService formService;
    @Autowired
    private FormSubmissionService submissionService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void manyVolunteersClickingAtOnce_keepOnePerPerson_andNothingButBusinessRejections() throws Exception {
        Long single = publish(true);
        Long multi = publish(false);
        List<FormVOs.Question> singleQs = formService.detail(single).getQuestions();
        List<FormVOs.Question> multiQs = formService.detail(multi).getQuestions();
        List<Long> volunteers = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            volunteers.add(FormTestSupport.volunteer(volunteerMapper, true));
        }

        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger multiOk = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        Random rnd = new Random(20260917L);
        for (Long v : volunteers) {
            for (int k = 0; k < 3; k++) {
                tasks.add(() -> track("每人一次", outcomes, failures, () -> submissionService.submit(single, v,
                        submit(List.of(a(singleQs.get(0).getId(), "A"), a(singleQs.get(1).getId(), "来一份"))))));
            }
            if (rnd.nextInt(2) == 0) {
                tasks.add(() -> {
                    if (track("不限次数", outcomes, failures, () -> submissionService.submit(multi, v,
                            submit(List.of(a(multiQs.get(0).getId(), "B"), a(multiQs.get(1).getId(), "再来")))))) {
                        multiOk.incrementAndGet();
                    }
                });
            }
            if (rnd.nextInt(6) == 0) {
                tasks.add(() -> track("坏答卷", outcomes, failures, () -> submissionService.submit(multi, v,
                        submit(List.of(a(multiQs.get(0).getId(), "Z"))))));
            }
        }
        Collections.shuffle(tasks, rnd);
        tasks.add(tasks.size() / 2, () -> track("停止收集", outcomes, failures, () -> {
            formService.close(multi);
            return null;
        }));

        long t0 = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable r : tasks) {
            futures.add(pool.submit(r));
        }
        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        long wall = System.currentTimeMillis() - t0;
        System.out.println("==== 问卷压测：" + tasks.size() + " 次 / " + THREADS + " 线程 / " + wall + " ms ====\n  "
                + new TreeMap<>(outcomes));

        assertTrue(failures.isEmpty(), "除业务拒绝外不应有任何异常（死锁、连接池超时都算）：" + failures);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT volunteer_id FROM org_form_submission "
                + "WHERE form_id = ? GROUP BY volunteer_id HAVING COUNT(*) <> 1) t", Integer.class, single),
                "「每人一次」那份：每人恰好一份");
        assertEquals(volunteers.size(), jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_form_submission WHERE form_id = ?", Integer.class, single));
        assertEquals(multiOk.get(), jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_form_submission WHERE form_id = ?", Integer.class, multi),
                "报成功的与落库的一一对应，停止收集之后没有漏进来的");
    }

    private boolean track(String kind, Map<String, AtomicInteger> outcomes, List<String> failures,
                          java.util.concurrent.Callable<?> action) {
        try {
            action.call();
            outcomes.computeIfAbsent(kind + "·成功", x -> new AtomicInteger()).incrementAndGet();
            return true;
        } catch (BusinessException e) {
            outcomes.computeIfAbsent(kind + "·拒绝·" + e.getMessage(), x -> new AtomicInteger()).incrementAndGet();
            return false;
        } catch (Exception e) {
            failures.add(kind + " → " + e);
            return false;
        }
    }

    private Long publish(boolean single) {
        FormDTOs.Save d = new FormDTOs.Save();
        d.setTitle("压测问卷-" + next());
        d.setSingleSubmit(single);
        d.setQuestions(List.of(q(QuestionType.SINGLE, "选一个", List.of("甲", "乙")), q(QuestionType.TEXT, "说一句", null)));
        Long id = formService.create(d, ADMIN);
        formService.publish(id, ADMIN);
        return id;
    }
}
