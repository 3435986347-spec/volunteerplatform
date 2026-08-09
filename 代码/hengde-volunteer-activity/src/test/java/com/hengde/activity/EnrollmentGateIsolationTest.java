package com.hengde.activity;

import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.dto.ProxyEnrollDTO;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.service.EnrollmentService;
import com.hengde.auth.constant.SanctionScope;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.entity.VolunteerSanction;
import com.hengde.auth.service.SanctionQueryService;
import com.hengde.auth.service.SanctionService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.VolunteerGroupMapper;
import com.hengde.organization.biz.dao.VolunteerGroupMemberMapper;
import com.hengde.organization.biz.entity.VolunteerGroup;
import com.hengde.organization.biz.entity.VolunteerGroupMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报名链路上<b>必须是当前读、必须互斥</b>的那几处判定的隔离级别用例。<b>需本机 Docker。</b>
 *
 * <p>这里的缺陷都是同一个形状：<b>「先查一下，再据此放行」，而查与放行之间隔着别人的一次提交</b>。
 * REPEATABLE READ 下事务里第一条一致性读就把读视图定死，此后普通查询永远读不到别人后来提交的改动。
 * 顺序调用的用例根本没有窗口，永远绿。</p>
 *
 * <p><b>确定性怎么来的</b>（沿用 {@code MedalReviewIsolationTest} 的手法，不靠 sleep）：
 * 可见性用「显式 RR 事务里先读建快照 → 另一连接提交 → 自检快照仍旧 → 再调被测方法」；
 * 互斥用「A 持锁不提交 → 观测 performance_schema 确认 B 真的在等这把锁 → 放行 A → B 继续」。
 * <b>自检那一步不能省</b>：它证明用例确实跑在目标窗口里，不成立的话后面测的是空气。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class EnrollmentGateIsolationTest {

    private static final AtomicLong SEQ = new AtomicLong();

    /** 活动起始时刻放在将来，避开报名截止判断 */
    private static final LocalDateTime A_START = LocalDateTime.now().plusDays(7).withNano(0);

    @Autowired
    private EnrollmentService enrollmentService;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ActivitySlotMapper activitySlotMapper;
    @Autowired
    private VolunteerGroupMapper groupMapper;
    @Autowired
    private VolunteerGroupMemberMapper groupMemberMapper;
    @Autowired
    private SanctionService sanctionService;
    @Autowired
    private SanctionQueryService sanctionQueryService;
    @Autowired
    private VolunteerQueryService volunteerQueryService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;
    /** 观测 performance_schema 用；业务账号无权限，故另开 root 连接（见 awaitLockWait） */
    @Autowired
    private MySQLContainer<?> mysqlContainer;

    private TransactionTemplate repeatableRead;

    @BeforeEach
    void initRepeatableReadTemplate() {
        repeatableRead = new TransactionTemplate(transactionTemplate.getTransactionManager());
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    // ================= ① 可见性：闸门读处置必须是当前读 =================

    /**
     * 快照建立之后才提交的处罚，闸门必须看得见。
     *
     * <p>父行锁只买到「对方提交不完我就等着」，买不到「等到之后能看见」。而
     * {@code doProxyEnroll} 出于隐私要求必须先跑同组校验（普通读），读视图在闸门之前就已建立。
     * 把 {@code throwIfBlocked} 里的当前读换回 {@code findBlocking}，本用例必红。</p>
     */
    @Test
    void gate_seesSanctionCommittedAfterSnapshotWasTaken() {
        Long vid = insertVolunteer();
        long sourceId = 70_000L + SEQ.incrementAndGet();

        repeatableRead.executeWithoutResult(status -> {
            assertEquals(0, activeSanctionCount(vid), "前置：此刻确实没有处置");

            commitInOtherThread(() -> sanctionService.impose(
                    vid, VolunteerSanction.SOURCE_REWARD_PUNISH, sourceId, SanctionScope.ACTIVITY, 7));

            assertEquals(0, activeSanctionCount(vid),
                    "RR 快照应仍读不到刚提交的处置，否则本用例覆盖不到目标窗口");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> sanctionQueryService.assertNotRestricted(vid, SanctionScope.ACTIVITY, "报名"),
                    "闸门读的是快照就会放行");
            assertTrue(ex.getMessage().contains("限制参加活动"), "实际：" + ex.getMessage());
        });
    }

    // ================= ② 可见性：资格判定同样必须是当前读 =================

    /**
     * 闸门当前读锁到了志愿者行，<b>后续的资格判定就不能再退回快照读</b>。
     *
     * <p>缺陷形态：{@code assertNoneRestricted} 把当前读到的行丢掉，
     * 而 {@code doProxyEnroll} 随后用普通 {@code getProfileForEligibility} 判「账号状态是否正常」——
     * 读的是同组校验那一刻的旧快照。于是「T1 建快照 → T2 禁用/注销目标并提交 →
     * T1 锁到新状态却不看 → T1 按旧快照放行」，一名已被禁用的志愿者照样被代报名进去。</p>
     *
     * <p>本用例把两条读放在一起对比：同一时刻、同一事务，快照读仍说「正常」，
     * 当前读已经说「禁用」。把 {@code EnrollmentService} 的三处改回快照读，
     * 生产代码就会用上面那个「正常」。</p>
     */
    @Test
    void eligibility_currentReadSeesBan_snapshotReadDoesNot() {
        Long vid = insertVolunteer();

        repeatableRead.executeWithoutResult(status -> {
            assertEquals(UserStatus.NORMAL, statusFromDb(vid), "前置：账号正常");

            commitInOtherThread(() -> {
                Volunteer banned = new Volunteer();
                banned.setId(vid);
                banned.setStatus(UserStatus.BANNED);
                volunteerMapper.updateById(banned);
            });

            assertEquals(UserStatus.NORMAL, statusFromDb(vid),
                    "RR 快照应仍读到「正常」，否则本用例覆盖不到目标窗口");

            // 快照读——这正是修复前报名链路用的那条，它会放行一个已被禁用的人
            assertEquals(UserStatus.NORMAL,
                    volunteerQueryService.getProfileForEligibility(vid).status(),
                    "自检：快照版确实读到过期值（这就是缺陷本身）");

            // 当前读——修复后报名链路用的那条
            assertEquals(UserStatus.BANNED,
                    volunteerQueryService.getProfileForEligibilityForShare(vid).status(),
                    "当前读必须看到刚提交的禁用；否则闸门锁到了真相却没有使用它");
        });
    }

    /**
     * 同一件事，但走<b>真实的生产入口</b> {@code EnrollmentService.proxyEnroll}。
     *
     * <p><b>为什么必须再来一条</b>：{@link #eligibility_currentReadSeesBan_snapshotReadDoesNot}
     * 直接比较两个查询方法，证明的是「当前读看得到、快照读看不到」这个<b>数据库性质</b>；
     * 它<b>没有</b>证明报名链路用的是哪一条——把 {@code EnrollmentService} 的三处
     * {@code getProfileForEligibilityForShare} 全改回快照版，那条用例照样绿。
     * 本用例调的是 {@code proxyEnroll} 本身，改回去必红。</p>
     *
     * <p>窗口是这么造出来的：外层是显式 RR 事务，先做一次普通读把读视图定死
     * （生产里对应 {@code doProxyEnroll} 出于隐私必须先跑的同组校验），
     * 再由另一条连接把目标禁用并提交。{@code proxyEnroll} 内部的
     * {@code transactionTemplate} 是 {@code REQUIRED}，会<b>加入</b>外层这个事务，
     * 于是它继承的正是那个已经过期的读视图——真实并发下的时序在这里被固定复现。</p>
     *
     * <p><b>末尾必须显式 {@code setRollbackOnly}</b>：内层事务是「参与者」，它异常回滚时
     * 只能把外层标记成 global-rollback-only；外层若照常提交，Spring 会抛
     * {@code UnexpectedRollbackException} 盖掉本用例真正的断言结果。
     * 显式标记后走的是本地回滚，不抛异常。（也正因为整个外层都会回滚，
     * 这里不再断言「没有报名行落库」——那条断言在这个结构下恒真，证明不了任何事；
     * 整批回滚由 {@code EnrollmentServiceTest.proxyEnroll_targetUnderSanction_rejectedRollsBackAll} 覆盖。）</p>
     */
    @Test
    void proxyEnroll_currentReadSeesBanTakenAfterSnapshot() {
        Long actor = insertVolunteer();
        Long target = insertVolunteer();
        insertGroupWithMembers(actor, List.of(target));
        Long aid = insertActivity();
        Long slot = insertSlot(aid);

        ProxyEnrollDTO dto = new ProxyEnrollDTO();
        dto.setVolunteerIds(List.of(target));
        dto.setSlotIds(List.of(slot));

        repeatableRead.executeWithoutResult(status -> {
            assertEquals(UserStatus.NORMAL, statusFromDb(target), "前置：账号正常");

            commitInOtherThread(() -> {
                Volunteer banned = new Volunteer();
                banned.setId(target);
                banned.setStatus(UserStatus.BANNED);
                volunteerMapper.updateById(banned);
            });

            assertEquals(UserStatus.NORMAL, statusFromDb(target),
                    "RR 快照应仍读到「正常」，否则本用例覆盖不到目标窗口");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> enrollmentService.proxyEnroll(aid, dto, actor),
                    "资格判定读快照就会把一名已被禁用的志愿者代报进去");
            assertTrue(ex.getMessage().contains("账号状态异常"), "实际：" + ex.getMessage());

            status.setRollbackOnly();
        });
    }

    // ================= ③ 互斥：写入方持父行 X 锁时，闸门必须阻塞 =================

    /**
     * {@code impose} 拿到志愿者父行的 X 锁、尚未提交时，闸门必须<b>等在那把锁上</b>，
     * 而不是先读到「没有处罚」再放行。
     *
     * <p><b>这一条与 ① 测的不是同一件事</b>：① 里处罚在闸门调用前就已经完全提交，两边没有时间重叠，
     * 把父行锁全部删掉它照样绿。本用例专门制造重叠——写入方<b>只拿了父行锁、还没插处置行</b>，
     * 此时闸门若不锁父行，就会查到「无处罚」并放行，而那条处罚马上就要落地。</p>
     *
     * <p><b>判据是 performance_schema 里真实的锁等待关系</b>（{@code volunteer} 这一行、
     * 等待方要 S、阻塞方持 X），不是「B 在 N 秒内没跑完」这类计时推断——线程没被调度、
     * GC 停顿都会产生同样的现象，那样的判据在实现错误时也可能为真。</p>
     *
     * <p><b>变异验证</b>：删掉 {@code assertNotRestricted} 里的
     * {@code volunteerMapper.selectByIdForShare(volunteerId)}，B 不再等待父行锁，
     * {@code observedWaiting} 为 false，用例红。</p>
     */
    @Test
    void gate_blocksWhileWriterHoldsParentRowLock() throws Exception {
        Long vid = insertVolunteer();
        long sourceId = 71_000L + SEQ.incrementAndGet();

        CountDownLatch writerHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A：拿住父行 X 锁（这正是 impose 的第一步），先不插处置行、不提交
            Future<?> writer = pool.submit(() -> transactionTemplate.executeWithoutResult(s -> {
                assertNotNull(volunteerMapper.selectByIdForUpdate(vid), "写入方应能锁到该行");
                writerHoldsLock.countDown();
                await(releaseWriter);
                // 放行后才真正施加处置，随即随本事务提交
                sanctionService.impose(vid, VolunteerSanction.SOURCE_REWARD_PUNISH,
                        sourceId, SanctionScope.ACTIVITY, 7);
            }));

            await(writerHoldsLock);

            // B：闸门。此刻处置行还不存在——不锁父行的话它会读到「没有处罚」并直接放行
            Future<?> gate = pool.submit(() -> transactionTemplate.executeWithoutResult(s ->
                    sanctionQueryService.assertNotRestricted(vid, SanctionScope.ACTIVITY, "报名")));

            boolean observedWaiting = awaitLockWait(15_000, vid, "S", "X");
            assertTrue(observedWaiting,
                    "闸门必须真的等在 volunteer 这一行的共享锁上（performance_schema 观测），"
                            + "否则它会在处罚落地前放行");

            releaseWriter.countDown();
            writer.get(30, TimeUnit.SECONDS);

            ExecutionException ee = assertThrows(ExecutionException.class,
                    () -> gate.get(30, TimeUnit.SECONDS),
                    "写入方提交后，闸门应看到那条处罚并拒绝");
            BusinessException ex = assertInstanceOf(BusinessException.class, ee.getCause());
            assertTrue(ex.getMessage().contains("限制参加活动"), "实际：" + ex.getMessage());
        } finally {
            releaseWriter.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * 批量闸门（代报名）同样要等父行锁。
     *
     * <p>⚠️ <b>覆盖边界要说清</b>：本用例证明的是「批量路径也会在父行锁上阻塞」，
     * <b>没有</b>证明它是「先把本批父行锁按 id 升序全部拿齐、再读处置」这个两阶段顺序——
     * 那个顺序是为了避开与 {@code impose} 的间隙锁成环，属于时序性质，
     * 用观测锁等待的手法区分不出来。它目前靠 {@code assertNoneRestricted} 的实现与注释保证。</p>
     */
    @Test
    void batchGate_blocksWhileWriterHoldsParentRowLockOfOneTarget() throws Exception {
        Long first = insertVolunteer();
        Long second = insertVolunteer();
        // 写入方锁的是 id 较大的那个，确保批量闸门要先走过 first 才会撞上
        Long locked = Math.max(first, second);
        Long other = Math.min(first, second);
        long sourceId = 72_000L + SEQ.incrementAndGet();

        CountDownLatch writerHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> transactionTemplate.executeWithoutResult(s -> {
                assertNotNull(volunteerMapper.selectByIdForUpdate(locked));
                writerHoldsLock.countDown();
                await(releaseWriter);
                sanctionService.impose(locked, VolunteerSanction.SOURCE_REWARD_PUNISH,
                        sourceId, SanctionScope.ACTIVITY, 7);
            }));

            await(writerHoldsLock);

            Future<?> gate = pool.submit(() -> transactionTemplate.executeWithoutResult(s ->
                    sanctionQueryService.assertNoneRestricted(
                            List.of(other, locked), SanctionScope.ACTIVITY, "被代报名")));

            assertTrue(awaitLockWait(15_000, locked, "S", "X"), "批量闸门也必须等在父行锁上");

            releaseWriter.countDown();
            writer.get(30, TimeUnit.SECONDS);

            ExecutionException ee = assertThrows(ExecutionException.class,
                    () -> gate.get(30, TimeUnit.SECONDS));
            assertInstanceOf(BusinessException.class, ee.getCause());
        } finally {
            releaseWriter.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * 反方向的承重用例：<b>{@code impose} 自己也必须取父行 X 锁</b>。
     *
     * <p><b>为什么必须再来一条</b>：上面两条 barrier 用例里，写入方的父行锁是<b>测试代码</b>
     * 自己调 {@code selectByIdForUpdate} 取的，然后才调 {@code impose}。
     * 把 {@code SanctionService.impose} 里那句 {@code selectByIdForUpdate} 整个删掉，
     * 那两条用例照样绿——它们证明的是「闸门会等父行锁」，不是「写入方会取父行锁」。
     * 两侧各自取锁才构成互斥，缺一边就没有边界。</p>
     *
     * <p>本用例把角色对调：先由一条事务持住 {@code volunteer} 那一行的 <b>S</b> 锁，
     * 再让 {@code impose} 去施加处置——它必须等在那把锁上（判据同样是
     * {@code performance_schema} 里真实的等待关系：请求 X、被 S 挡住）。
     * 删掉 {@code impose} 里的父行锁，它会直接 INSERT 进 {@code volunteer_sanction}
     * （另一张表、不碰这一行），{@code observedWaiting} 为 false，用例红。</p>
     */
    @Test
    void impose_blocksWhileReaderHoldsParentRowLock() throws Exception {
        Long vid = insertVolunteer();
        long sourceId = 73_000L + SEQ.incrementAndGet();

        CountDownLatch readerHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A：持住父行 S 锁（闸门的第一步就是这个），不提交
            Future<?> reader = pool.submit(() -> transactionTemplate.executeWithoutResult(s -> {
                assertNotNull(volunteerMapper.selectByIdForShare(vid), "读方应能锁到该行");
                readerHoldsLock.countDown();
                await(releaseReader);
            }));

            await(readerHoldsLock);

            // B：写入方。它若不锁父行，就会绕过这条串行化边界直接把处置插进去
            Future<?> writer = pool.submit(() -> transactionTemplate.executeWithoutResult(s ->
                    sanctionService.impose(vid, VolunteerSanction.SOURCE_REWARD_PUNISH,
                            sourceId, SanctionScope.ACTIVITY, 7)));

            assertTrue(awaitLockWait(15_000, vid, "X", "S"),
                    "impose 必须真的等在 volunteer 这一行的排他锁上（performance_schema 观测），"
                            + "否则闸门与写入方之间没有任何共同的串行化边界");

            releaseReader.countDown();
            reader.get(30, TimeUnit.SECONDS);
            writer.get(30, TimeUnit.SECONDS);

            assertEquals(1, activeSanctionCount(vid), "读方释放后处置应当照常落地");
        } finally {
            releaseReader.countDown();
            pool.shutdownNow();
        }
    }

    // ================= ④ 事务外调用必须响亮失败 =================

    /** 不在事务里，{@code FOR SHARE} 随语句释放、串行化边界静默消失，所以必须当场炸掉。 */
    @Test
    void gate_outsideTransaction_failsLoudly() {
        Long vid = insertVolunteer();
        assertThrows(IllegalStateException.class,
                () -> sanctionQueryService.assertNotRestricted(vid, SanctionScope.ACTIVITY, "报名"));
        assertThrows(IllegalStateException.class,
                () -> sanctionQueryService.assertNoneRestricted(
                        List.of(vid), SanctionScope.ACTIVITY, "被代报名"));
    }

    // ---------- 夹具 ----------

    /**
     * 观测 {@code volunteer} 某一行上真实的锁等待关系：有人要 {@code requestMode}、
     * 被持 {@code blockingMode} 的人挡住。
     *
     * <p>Testcontainers 的业务账号读不到 performance_schema，故另开一条 root 连接。</p>
     *
     * @param requestMode  等待方申请的锁模式前缀（{@code "S"} 或 {@code "X"}）
     * @param blockingMode 阻塞方持有的锁模式前缀
     */
    private boolean awaitLockWait(long millis, Long volunteerId,
                                  String requestMode, String blockingMode) throws Exception {
        String sql = "SELECT COUNT(*)"
                + "  FROM performance_schema.data_lock_waits w"
                + "  JOIN performance_schema.data_locks req"
                + "    ON req.ENGINE = w.ENGINE AND req.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID"
                + "  JOIN performance_schema.data_locks blk"
                + "    ON blk.ENGINE = w.ENGINE AND blk.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID"
                + " WHERE req.OBJECT_SCHEMA = ? AND req.OBJECT_NAME = 'volunteer'"
                + "   AND req.INDEX_NAME = 'PRIMARY' AND req.LOCK_TYPE = 'RECORD'"
                + "   AND req.LOCK_MODE LIKE ? AND req.LOCK_DATA = ?"
                + "   AND blk.OBJECT_SCHEMA = req.OBJECT_SCHEMA AND blk.OBJECT_NAME = 'volunteer'"
                + "   AND blk.INDEX_NAME = 'PRIMARY' AND blk.LOCK_TYPE = 'RECORD'"
                + "   AND blk.LOCK_MODE LIKE ? AND blk.LOCK_DATA = req.LOCK_DATA";
        long deadline = System.currentTimeMillis() + millis;
        try (Connection conn = DriverManager.getConnection(
                mysqlContainer.getJdbcUrl(), "root", mysqlContainer.getPassword());
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, mysqlContainer.getDatabaseName());
            ps.setString(2, requestMode + "%");
            ps.setString(3, String.valueOf(volunteerId));
            ps.setString(4, blockingMode + "%");
            while (System.currentTimeMillis() < deadline) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        return true;
                    }
                }
                Thread.sleep(50);
            }
        }
        return false;
    }

    /** 用 JdbcTemplate 直查：走本事务连接（同一读视图），但每次都真的发 SQL，绕开 MyBatis 一级缓存。 */
    private int activeSanctionCount(Long volunteerId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM volunteer_sanction"
                        + " WHERE volunteer_id = ? AND status = 1 AND is_deleted = 0",
                Integer.class, volunteerId);
        return n == null ? 0 : n;
    }

    private Integer statusFromDb(Long volunteerId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM volunteer WHERE id = ? AND is_deleted = 0", Integer.class, volunteerId);
    }

    private void commitInOtherThread(Runnable action) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(action).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("并发前置写入失败", e);
        } finally {
            pool.shutdownNow();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发屏障超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Long insertVolunteer() {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_gate_" + System.nanoTime() + "_" + SEQ.incrementAndGet());
        v.setRealName("闸门用例");
        v.setStatus(UserStatus.NORMAL);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    /** 已发布、无需审核、不设人数与资格门槛的活动——本类只关心资格判定读的是快照还是当前值。 */
    private Long insertActivity() {
        Activity a = new Activity();
        a.setTitle("闸门用例活动_" + System.nanoTime());
        a.setStartTime(A_START);
        a.setEndTime(A_START.plusHours(8));
        a.setStatus(1);
        a.setNeedAudit(0);
        a.setMinProjects(0);
        a.setRequireMinJoinCount(0);
        activityMapper.insert(a);
        a.setSerialNo(a.getId());
        activityMapper.updateById(a);
        return a.getId();
    }

    private Long insertSlot(Long activityId) {
        ActivitySlot slot = new ActivitySlot();
        slot.setActivityId(activityId);
        slot.setProjectName("项目_" + System.nanoTime());
        slot.setStartTime(A_START);
        slot.setEndTime(A_START.plusHours(2));
        slot.setNeedCount(5);
        activitySlotMapper.insert(slot);
        return slot.getId();
    }

    /** 建一个 ACTIVE 小组：actor 为组长，其余为 ACTIVE 成员（代报名要求同组）。 */
    private void insertGroupWithMembers(Long actorId, List<Long> memberIds) {
        VolunteerGroup g = new VolunteerGroup();
        g.setGroupNo("G_gate_" + System.nanoTime());
        g.setName("闸门用例小组_" + System.nanoTime());
        g.setLeaderId(actorId);
        g.setStatus(1);
        groupMapper.insert(g);
        insertMember(g.getId(), actorId, 1);
        for (Long mid : memberIds) {
            insertMember(g.getId(), mid, 0);
        }
    }

    private void insertMember(Long groupId, Long volunteerId, int role) {
        VolunteerGroupMember m = new VolunteerGroupMember();
        m.setGroupId(groupId);
        m.setVolunteerId(volunteerId);
        m.setRole(role);
        m.setStatus(1);
        m.setApplyTime(LocalDateTime.now());
        m.setAuditTime(LocalDateTime.now());
        groupMemberMapper.insert(m);
    }
}
