package com.hengde.organization.form;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.form.dto.FormDTOs;
import com.hengde.organization.form.service.FormService;
import com.hengde.organization.form.service.FormSubmissionService;
import com.hengde.organization.form.support.QuestionType;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.hengde.organization.form.FormTestSupport.ADMIN;
import static com.hengde.organization.form.FormTestSupport.a;
import static com.hengde.organization.form.FormTestSupport.next;
import static com.hengde.organization.form.FormTestSupport.q;
import static com.hengde.organization.form.FormTestSupport.submit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 问卷提交的并发（<b>必跑</b>）。
 *
 * <p>① <b>停止收集与提交串行</b>——不靠调度去撞：用裸 JDBC 事务先把「停止」写进去但不提交（持有问卷行的排他锁），
 * 再放一个提交进来，断言它<b>被挡住</b>；提交「停止」之后，它看到的是已停止、被拒绝。
 * 提交若用普通快照读，它根本不会被挡住，会读到「收集中」并交成功——这条用例就是为了把这件事钉住。</p>
 *
 * <p>② 同一个人 12 个线程同时交一份「每人一次」的问卷：恰好一份成功，其余报「已经提交过」，没有死锁。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class FormSubmitConcurrencyTest {

    @Autowired
    private FormService formService;
    @Autowired
    private FormSubmissionService submissionService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aSubmitWaitsForAnInFlightClose_andThenSeesItClosed() throws Exception {
        Long formId = publishOneQuestion(true);
        Long questionId = formService.detail(formId).getQuestions().get(0).getId();
        Long me = FormTestSupport.volunteer(volunteerMapper, true);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection closer = dataSource.getConnection()) {
            closer.setAutoCommit(false);
            try (PreparedStatement ps = closer.prepareStatement("UPDATE org_form SET status = 2 WHERE id = ?")) {
                ps.setLong(1, formId);
                assertEquals(1, ps.executeUpdate());
            }
            Future<Long> submitting = pool.submit(() ->
                    submissionService.submit(formId, me, submit(List.of(a(questionId, "停止前一刻")))));
            assertThrows(TimeoutException.class, () -> submitting.get(800, TimeUnit.MILLISECONDS),
                    "「停止」还没提交，提交必须等它——没等说明读的是快照");
            closer.commit();
            ExecutionException e = assertThrows(ExecutionException.class, () -> submitting.get(10, TimeUnit.SECONDS));
            assertInstanceOf(BusinessException.class, e.getCause());
            assertEquals("问卷不存在或已停止收集", e.getCause().getMessage());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM org_form_submission WHERE form_id = ?",
                Integer.class, formId), "停止之后不能再收进答卷");
    }

    @Test
    void sameVolunteerSubmittingTwelveTimesAtOnce_getsExactlyOneSubmission() throws Exception {
        Long formId = publishOneQuestion(true);
        Long questionId = formService.detail(formId).getQuestions().get(0).getId();
        Long me = FormTestSupport.volunteer(volunteerMapper, true);
        int n = 12;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return submissionService.submit(formId, me, submit(List.of(a(questionId, "第 " + k + " 次"))));
            }));
        }
        int ok = 0;
        int duplicate = 0;
        for (Future<Long> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
                ok++;
            } catch (ExecutionException e) {
                assertInstanceOf(BusinessException.class, e.getCause(), "只允许业务拒绝，死锁也算失败：" + e.getCause());
                assertEquals("你已经提交过这份问卷了", e.getCause().getMessage());
                duplicate++;
            }
        }
        pool.shutdown();
        assertEquals(1, ok);
        assertEquals(n - 1, duplicate);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM org_form_submission WHERE form_id = ? AND volunteer_id = ?",
                Integer.class, formId, me));
    }

    @Test
    void differentVolunteersSubmittingAtOnce_allSucceed() throws Exception {
        Long formId = publishOneQuestion(true);
        Long questionId = formService.detail(formId).getQuestions().get(0).getId();
        int n = 20;
        List<Long> volunteers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            volunteers.add(FormTestSupport.volunteer(volunteerMapper, true));
        }
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<Long>> futures = new ArrayList<>();
        for (Long v : volunteers) {
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return submissionService.submit(formId, v, submit(List.of(a(questionId, "我是 " + v))));
            }));
        }
        for (Future<Long> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(n, jdbc.queryForObject("SELECT COUNT(*) FROM org_form_submission WHERE form_id = ?",
                Integer.class, formId));
    }

    private Long publishOneQuestion(boolean single) {
        FormDTOs.Save d = new FormDTOs.Save();
        d.setTitle("并发问卷-" + next());
        d.setSingleSubmit(single);
        d.setQuestions(List.of(q(QuestionType.TEXT, "说点什么", null)));
        Long id = formService.create(d, ADMIN);
        formService.publish(id, ADMIN);
        return id;
    }
}
