package com.hengde.data.complaint;

import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.AdminUser;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RecordingSmsConfig;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.data.complaint.dto.ComplaintDTOs;
import com.hengde.data.complaint.service.ComplaintService;
import com.hengde.data.complaint.service.ComplaintService.Scope;
import com.hengde.data.complaint.support.ComplaintFlow;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投诉建议压力测试（{@code @Tag("stress")}，默认不跑；{@code -Dtest.excludedGroups=none -Dgroups=stress}）。
 *
 * <p>80 条工单，三个部门的 6 个处理人乱序地受理 / 流转 / 答复 / 备注，另有志愿者连点提交撞每日上限。断言每条工单的
 * 「工单行」与「进度行」对得上：当前部门＝最后一次提交或流转的去向、流转次数＝流转进度条数、已办结的恰好一条答复、
 * 没办结的没有答复；每人提交数不超过上限；除业务拒绝外零异常。</p>
 *
 * @author hengde
 */
@Tag("stress")
@SpringBootTest(properties = {"hengde.data.complaint.default-department=监察部", "hengde.data.complaint.daily-limit=4"})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, RecordingSmsConfig.class})
class ComplaintStressTest {

    private static final int THREADS = 16;
    private static final String[] DEPTS = {"监察部", "宣传部", "组织部"};
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L + 800_000L);

    @Autowired
    private ComplaintService complaintService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private AdminUserMapper adminUserMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void handlersChurningOverTickets_keepTicketsAndProgressConsistent() throws Exception {
        List<Scope> handlers = new ArrayList<>();
        for (String d : DEPTS) {
            handlers.add(new Scope(admin(d), d, false));
            handlers.add(new Scope(admin(d), d, false));
        }
        List<Long> tickets = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            tickets.add(complaintService.submit(volunteer(), submit("工单 " + i)));
        }
        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        Random rnd = new Random(43L);
        List<Runnable> tasks = new ArrayList<>();
        for (Long id : tickets) {
            for (int k = 0; k < 6; k++) {
                Scope s = handlers.get(rnd.nextInt(handlers.size()));
                int roll = rnd.nextInt(10);
                String to = DEPTS[rnd.nextInt(DEPTS.length)];
                if (roll < 3) {
                    tasks.add(() -> track("受理", outcomes, failures, () -> complaintService.accept(s, id)));
                } else if (roll < 6) {
                    tasks.add(() -> track("流转", outcomes, failures, () -> complaintService.transfer(s, id, to, "压测")));
                } else if (roll < 8) {
                    tasks.add(() -> track("答复", outcomes, failures, () -> complaintService.reply(s, id, "压测答复")));
                } else {
                    tasks.add(() -> track("备注", outcomes, failures, () -> complaintService.note(s, id, "压测备注")));
                }
            }
        }
        List<Long> clickers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Long v = volunteer();
            clickers.add(v);
            for (int k = 0; k < 8; k++) {
                tasks.add(() -> track("连点提交", outcomes, failures, () -> complaintService.submit(v, submit("连点"))));
            }
        }
        Collections.shuffle(tasks, rnd);

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
        System.out.println("==== 投诉建议压测：" + tasks.size() + " 次 / " + THREADS + " 线程 / "
                + (System.currentTimeMillis() - t0) + " ms ====\n  " + new TreeMap<>(outcomes));

        assertTrue(failures.isEmpty(), "除业务拒绝外不应有任何异常（死锁也算）：" + failures);
        for (Long id : tickets) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, current_department, transfer_count FROM data_complaint WHERE id = ?", id);
            String lastDept = jdbc.queryForObject("SELECT to_department FROM data_complaint_log WHERE complaint_id = ? "
                    + "AND action IN (1, 3) ORDER BY id DESC LIMIT 1", String.class, id);
            assertEquals(lastDept, row.get("current_department"), "工单 " + id + "：当前部门＝最后一次去向");
            assertEquals(((Number) row.get("transfer_count")).intValue(), jdbc.queryForObject(
                    "SELECT COUNT(*) FROM data_complaint_log WHERE complaint_id = ? AND action = 3", Integer.class, id),
                    "工单 " + id + "：流转次数＝流转进度条数");
            int replies = jdbc.queryForObject("SELECT COUNT(*) FROM data_complaint_log WHERE complaint_id = ? AND action = 4",
                    Integer.class, id);
            boolean closed = ((Number) row.get("status")).intValue() == ComplaintFlow.CLOSED;
            assertEquals(closed ? 1 : 0, replies, "工单 " + id + "：已办结恰好一条答复，没办结没有答复");
        }
        for (Long v : clickers) {
            assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM data_complaint WHERE volunteer_id = ?",
                    Integer.class, v), "连点 8 次，恰好停在每日上限");
        }
    }

    private static void track(String kind, Map<String, AtomicInteger> outcomes, List<String> failures, Runnable action) {
        try {
            action.run();
            outcomes.computeIfAbsent(kind + "·成功", x -> new AtomicInteger()).incrementAndGet();
        } catch (BusinessException e) {
            String reason = e.getMessage().replaceAll("（当前：.*", "（当前：…）").replaceAll("「.*」", "「…」");
            outcomes.computeIfAbsent(kind + "·拒绝·" + reason, x -> new AtomicInteger()).incrementAndGet();
        } catch (Exception e) {
            failures.add(kind + " → " + e);
        }
    }

    private Long volunteer() {
        Volunteer v = new Volunteer();
        v.setOpenid("test:complaint-st:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setStatus(0);
        v.setManagerFlag(0);
        volunteerMapper.insert(v);
        return v.getId();
    }

    private Long admin(String department) {
        AdminUser a = new AdminUser();
        a.setUsername("complaint-st-" + System.nanoTime() + "-" + SEQ.incrementAndGet());
        a.setPassword("x");
        a.setDepartment(department);
        a.setIsSuperAdmin(0);
        a.setStatus(0);
        adminUserMapper.insert(a);
        return a.getId();
    }

    private static ComplaintDTOs.Submit submit(String content) {
        ComplaintDTOs.Submit d = new ComplaintDTOs.Submit();
        d.setType(ComplaintFlow.TYPE_SUGGESTION);
        d.setContent(content);
        return d;
    }
}
