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
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投诉建议的并发（<b>必跑</b>）。
 *
 * <p>① <b>输家看到的是刚发生的事，不是自己读到的旧样子</b>——确定性构造：裸 JDBC 事务先把工单转走并办结、不提交
 * （持有这一行的排他锁），再放一个答复进来：它的预检读到的是旧的「处理中」，CAS 被挡住；放行之后 CAS 落空，
 * 报错必须说「当前：已办结，在宣传部」。报错若拿读到的旧对象拼，或在同一事务里用普通读复核，会说成「处理中」。</p>
 *
 * <p>①′ 答复撞上正在进行的流转：确定性构造，钉住 CAS 条件里的「读到时的部门」（赛跑用例撞不到那个窗口）。</p>
 *
 * <p>② 流转对答复 30 轮偏置起跑：每轮恰好一个成功、终态与赢家一致，且两种结局都出现过（否则某条分支根本没跑到）。
 * ③ 同一工单 10 个线程同时受理，只成一个。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = "hengde.data.complaint.default-department=监察部")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, RecordingSmsConfig.class})
class ComplaintConcurrencyTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L + 500_000L);

    @Autowired
    private ComplaintService complaintService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private AdminUserMapper adminUserMapper;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;

    private Scope supervisor;

    @BeforeEach
    void setUp() {
        supervisor = new Scope(admin("监察部"), "监察部", false);
        admin("宣传部");
    }

    @Test
    void theLoserIsToldWhatJustHappened_notWhatItReadBefore() throws Exception {
        Long id = newComplaint();
        complaintService.accept(supervisor, id);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement ps = other.prepareStatement(
                    "UPDATE data_complaint SET current_department = '宣传部', status = 2 WHERE id = ?")) {
                ps.setLong(1, id);
                assertEquals(1, ps.executeUpdate());
            }
            Future<Void> replying = pool.submit(() -> {
                complaintService.reply(supervisor, id, "晚了一步的答复");
                return null;
            });
            assertThrows(TimeoutException.class, () -> replying.get(800, TimeUnit.MILLISECONDS),
                    "对方还没提交，答复的 CAS 必须等它（预检读到的是旧的「处理中」）");
            other.commit();
            ExecutionException e = assertThrows(ExecutionException.class, () -> replying.get(10, TimeUnit.SECONDS));
            assertInstanceOf(BusinessException.class, e.getCause());
            assertEquals("工单刚刚被处理过（当前：已办结，在宣传部），请刷新后再操作", e.getCause().getMessage());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM data_complaint_log WHERE complaint_id = ? AND action = ?",
                Integer.class, id, ComplaintFlow.ACT_REPLY), "输家不留进度");
    }

    /**
     * CAS 条件里的「读到时的部门」是承重的——赛跑用例几乎撞不到这个窗口（预检读与 CAS 之间只有几微秒），
     * 所以单独确定性地构造：裸 JDBC 事务把工单转去宣传部（回到待受理）但不提交，监察部的答复预检读到的还是
     * 「处理中、在监察部」，CAS 被挡住；放行后工单状态是「待受理」，只看状态的 CAS 会照样命中，
     * 让监察部把一张已经转走的工单答复办结。（变异验证撞出来的缺口：去掉部门条件时赛跑用例照样全绿。）
     */
    @Test
    void aReplyRacingATransfer_doesNotCloseATicketThatJustLeftTheDepartment() throws Exception {
        Long id = newComplaint();
        complaintService.accept(supervisor, id);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement ps = other.prepareStatement(
                    "UPDATE data_complaint SET current_department = '宣传部', status = 0, accept_by = NULL WHERE id = ?")) {
                ps.setLong(1, id);
                assertEquals(1, ps.executeUpdate());
            }
            Future<Void> replying = pool.submit(() -> {
                complaintService.reply(supervisor, id, "工单已经被转走了还在答复");
                return null;
            });
            assertThrows(TimeoutException.class, () -> replying.get(800, TimeUnit.MILLISECONDS),
                    "流转还没提交，答复的 CAS 必须等它");
            other.commit();
            ExecutionException e = assertThrows(ExecutionException.class, () -> replying.get(10, TimeUnit.SECONDS));
            assertInstanceOf(BusinessException.class, e.getCause());
            assertEquals("工单刚刚被处理过（当前：待受理，在宣传部），请刷新后再操作", e.getCause().getMessage());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(ComplaintFlow.PENDING, jdbc.queryForObject("SELECT status FROM data_complaint WHERE id = ?",
                Integer.class, id), "工单还在宣传部待受理，没被监察部答复办结");
    }

    @Test
    void transferRacingReply_exactlyOneWins_andBothOutcomesOccur() throws Exception {
        int transferWins = 0;
        int replyWins = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int round = 0; round < 30; round++) {
            Long id = newComplaint();
            long transferDelay = round % 3 == 1 ? 15 : 0;
            long replyDelay = round % 3 == 0 ? 15 : 0;
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Boolean> transfer = pool.submit(attempt(barrier, transferDelay,
                    () -> complaintService.transfer(supervisor, id, "宣传部", "赛跑")));
            Future<Boolean> reply = pool.submit(attempt(barrier, replyDelay,
                    () -> complaintService.reply(supervisor, id, "赛跑答复")));
            boolean t = transfer.get(30, TimeUnit.SECONDS);
            boolean r = reply.get(30, TimeUnit.SECONDS);
            assertEquals(1, (t ? 1 : 0) + (r ? 1 : 0), "第 " + round + " 轮恰好一个成功");
            Map<String, Object> row = jdbc.queryForMap("SELECT status, current_department, transfer_count FROM data_complaint "
                    + "WHERE id = ?", id);
            if (t) {
                transferWins++;
                assertEquals("宣传部", row.get("current_department"));
                assertEquals(ComplaintFlow.PENDING, ((Number) row.get("status")).intValue());
                assertEquals(1, ((Number) row.get("transfer_count")).intValue());
            } else {
                replyWins++;
                assertEquals("监察部", row.get("current_department"));
                assertEquals(ComplaintFlow.CLOSED, ((Number) row.get("status")).intValue());
            }
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM data_complaint_log WHERE complaint_id = ? "
                    + "AND action IN (3, 4)", Integer.class, id), "进度里只有赢家那一步");
        }
        pool.shutdown();
        assertTrue(transferWins > 0 && replyWins > 0,
                "两种结局都要出现过，否则有一条分支根本没被跑到：流转赢 " + transferWins + " / 答复赢 " + replyWins);
    }

    @Test
    void tenAdminsAcceptingAtOnce_onlyOneSucceeds() throws Exception {
        Long id = newComplaint();
        int n = 10;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Scope s = new Scope(admin("监察部"), "监察部", false);
            futures.add(pool.submit(attempt(barrier, 0, () -> complaintService.accept(s, id))));
        }
        int ok = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(30, TimeUnit.SECONDS)) {
                ok++;
            }
        }
        pool.shutdown();
        assertEquals(1, ok);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM data_complaint_log WHERE complaint_id = ? AND action = ?",
                Integer.class, id, ComplaintFlow.ACT_ACCEPT));
    }

    /** 跑一次动作：成功 true、业务拒绝 false、其它异常原样抛（死锁之类都算失败）。 */
    private static Callable<Boolean> attempt(CyclicBarrier barrier, long delayMillis, Runnable action) {
        return () -> {
            barrier.await(10, TimeUnit.SECONDS);
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            try {
                action.run();
                return true;
            } catch (BusinessException e) {
                return false;
            }
        };
    }

    private Long newComplaint() {
        Volunteer v = new Volunteer();
        v.setOpenid("test:complaint-cc:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setStatus(0);
        v.setManagerFlag(0);
        volunteerMapper.insert(v);
        ComplaintDTOs.Submit d = new ComplaintDTOs.Submit();
        d.setType(ComplaintFlow.TYPE_COMPLAINT);
        d.setContent("并发工单 " + SEQ.get());
        return complaintService.submit(v.getId(), d);
    }

    private Long admin(String department) {
        AdminUser a = new AdminUser();
        a.setUsername("complaint-cc-" + System.nanoTime() + "-" + SEQ.incrementAndGet());
        a.setPassword("x");
        a.setDepartment(department);
        a.setIsSuperAdmin(0);
        a.setStatus(0);
        adminUserMapper.insert(a);
        return a.getId();
    }
}
