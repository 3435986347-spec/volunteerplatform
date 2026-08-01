package com.hengde.honor;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.honor.constant.MedalConditionType;
import com.hengde.honor.constant.MedalGrantStatus;
import com.hengde.honor.constant.MedalStatus;
import com.hengde.honor.dao.HonorMedalGrantMapper;
import com.hengde.honor.dao.HonorMedalMapper;
import com.hengde.honor.dto.MedalGrantDTO;
import com.hengde.honor.dto.MedalSaveDTO;
import com.hengde.honor.entity.HonorMedal;
import com.hengde.honor.entity.HonorMedalGrant;
import com.hengde.honor.service.MedalGrantService;
import com.hengde.honor.service.MedalService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 勋章双重审核的<b>隔离级别 / 并发边界</b>用例。<b>需本机 Docker</b>（MySQL + Redis）。
 *
 * <p>这里的四条缺陷都有同一个形状：<b>「先查一下，再据此动作」，而查与动作之间隔着别人的一次提交</b>。
 * MySQL 默认 REPEATABLE READ 下，事务里第一条 SELECT 就把读视图定死了，此后普通查询永远读不到
 * 别人后来提交的改动——于是「查的时候还启用着」和「动作生效的时候仍然启用着」是两回事。
 * 顺序调用的用例（先停用、再审核）无法覆盖这个窗口，因为那里根本没有窗口。</p>
 *
 * <p><b>如何把并发做成确定性的</b>（沿用 {@code PointServiceTest} 的既有手法，不靠 sleep 撞运气）：
 * 在一个显式 {@code ISOLATION_REPEATABLE_READ} 的事务里先做一次读建立快照 → 让<b>另一个连接</b>
 * 完成改动并提交 → 回到本事务断言「快照仍看不到对方的改动」（这一步是<b>用例有效性的自检</b>，
 * 不成立就说明没跑在 RR 下，后面测的东西会退化成空气）→ 再调被测方法。
 * 被测方法若用普通查询就必然读到过期值，用当前读（{@code FOR SHARE}）则读到真值。</p>
 *
 * <p><b>自检必须绕开 MyBatis 一级缓存</b>，所以这里用 {@link JdbcTemplate} 而不是 Mapper。
 * MyBatis 的 localCacheScope 默认是 SESSION：同一个 SqlSession 里两次「完全相同」的查询，
 * 第二次直接返回第一次缓存的对象，<b>根本不会发 SQL</b>。若自检写成两次
 * {@code medalMapper.selectById(id)}，那它证明的只是「一级缓存生效」，与 RR 快照毫无关系——
 * 用例会照常变绿，而真正想覆盖的窗口一次也没被验证到。{@code JdbcTemplate} 经
 * {@code DataSourceUtils} 取的是同一个事务连接（同一读视图），但每次都真的执行 SQL。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class MedalReviewIsolationTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final Long AUDITOR = 9101L;
    private static final Long APPLICANT = 9102L;

    private MedalService medalService;
    private MedalGrantService medalGrantService;
    private HonorMedalMapper medalMapper;
    private HonorMedalGrantMapper grantMapper;
    private VolunteerMapper volunteerMapper;
    private TransactionTemplate transactionTemplate;
    private JdbcTemplate jdbcTemplate;
    private MySQLContainer<?> mysqlContainer;

    private TransactionTemplate repeatableRead;

    @Autowired
    public void setMedalService(MedalService medalService) {
        this.medalService = medalService;
    }

    @Autowired
    public void setMedalGrantService(MedalGrantService medalGrantService) {
        this.medalGrantService = medalGrantService;
    }

    @Autowired
    public void setMedalMapper(HonorMedalMapper medalMapper) {
        this.medalMapper = medalMapper;
    }

    @Autowired
    public void setGrantMapper(HonorMedalGrantMapper grantMapper) {
        this.grantMapper = grantMapper;
    }

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    @Autowired
    public void setTransactionTemplate(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    @Autowired
    public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 观测 performance_schema 用；业务账号无权限，故另开 root 连接（见 awaitLockWait） */
    @Autowired
    public void setMysqlContainer(MySQLContainer<?> mysqlContainer) {
        this.mysqlContainer = mysqlContainer;
    }

    @BeforeEach
    void initRepeatableReadTemplate() {
        repeatableRead = new TransactionTemplate(transactionTemplate.getTransactionManager());
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    // ================= ① 只改排序不得覆盖整行 =================

    /**
     * 「只改排序」不得把读那一刻的状态与内容整行写回去。
     *
     * <p>缺陷形态：{@code updateSort} 曾是「{@code selectById} 读出实体 → 改 sort →
     * {@code updateById} 写回」。并发时另一个线程刚把已启用勋章改成待审核（{@link MedalService#update}），
     * 这边的排序保存就会连同旧的 {@code status=已启用} 和旧内容一起写回来——<b>重审凭空消失，
     * 未经审核的样式重新变成可发放</b>。修复是只发一条 {@code SET sort, update_time WHERE id}。</p>
     */
    @Test
    void updateSort_doesNotResurrectStaleReviewState() {
        Long id = enabledMedal("排序覆盖");

        repeatableRead.executeWithoutResult(status -> {
            // ① 建立快照：此刻是「已启用」
            assertEquals(MedalStatus.ENABLED, statusFromDb(id));
            // ② 另一个连接把它改掉 → 退回待审核，并提交
            commitInOtherThread(() -> medalService.update(id, dto("排序覆盖-改后")));
            // 用例有效性自检：对方已提交，本事务快照仍应看到旧值（JdbcTemplate，绕开 MyBatis 一级缓存）
            assertEquals(MedalStatus.ENABLED, statusFromDb(id),
                    "RR 快照应仍读到旧状态，否则本用例覆盖不到「读到过期值再写回」的窗口");
            // ③ 此时保存排序
            medalService.updateSort(id, 77);
        });

        HonorMedal after = medalMapper.selectById(id);
        assertEquals(MedalStatus.PENDING, after.getStatus(),
                "排序保存不得把并发写入的「待审核」覆盖回「已启用」——那是一条绕过样式审核的路");
        assertEquals(77, after.getSort(), "排序本身应生效");
    }

    // ================= ② 修改与样式审核不得互相穿过 =================

    /**
     * 修改与「提交审核」并发时，修改必须失败，不能把内容悄悄写进别人正在审的那一版。
     *
     * <p>光在方法开头 {@code if (status == 待审核) throw} 挡不住：那个判断读的是快照。
     * 必须把 {@code status <> 待审核} 放进 UPDATE 的 WHERE 里，靠影响行数判定——
     * 一条 UPDATE 语句本身是原子的。</p>
     */
    @Test
    void update_failsWhenMedalEnteredReviewConcurrently() {
        Long id = medalService.create(dto("并发提交"));

        repeatableRead.executeWithoutResult(status -> {
            assertEquals(MedalStatus.DRAFT, statusFromDb(id));
            // 另一个连接把它提交审核了
            commitInOtherThread(() -> medalService.submit(id));
            assertEquals(MedalStatus.DRAFT, statusFromDb(id),
                    "RR 快照应仍读到草稿态，否则本用例测不到目标窗口");

            MedalSaveDTO changed = dto("并发提交-改后");
            changed.setIconUrl("https://example.com/swapped.png");
            assertThrows(BusinessException.class, () -> medalService.update(id, changed),
                    "对方已提交审核，这次修改必须失败，而不是改掉待审内容");
        });

        assertEquals("https://example.com/medal.png", medalMapper.selectById(id).getIconUrl(),
                "待审内容不该被改动");
        assertEquals(MedalStatus.PENDING, medalMapper.selectById(id).getStatus());
    }

    // ================= ③ 发放审核必须看到最新的样式状态 =================

    /**
     * 发放审核开始后勋章被停用，本次审核必须失败——不能靠快照里那个「已启用」放行。
     *
     * <p>缺陷形态：{@code approve} 先普通读发放记录（读视图就此定死），再普通读勋章。
     * 中间另一个事务把勋章停用并提交，第二条查询仍读到「已启用」，
     * <b>于是一枚已撤下的勋章照样生效、附带积分照样入账</b>。修复是用 {@code FOR SHARE} 当前读。</p>
     */
    @Test
    void approveGrant_seesConcurrentDisable() {
        Long medal = enabledMedal("审核中停用");
        Long volunteer = volunteer("审核中停用小刘");
        Long grantId = medalGrantService.apply(grantDto(medal, volunteer), APPLICANT);

        repeatableRead.executeWithoutResult(status -> {
            // ① 建立快照：勋章仍是已启用
            assertEquals(MedalStatus.ENABLED, statusFromDb(medal));
            // ② 另一个连接停用它并提交
            commitInOtherThread(() -> medalService.disable(medal));
            assertEquals(MedalStatus.ENABLED, statusFromDb(medal),
                    "RR 快照应仍读到已启用，否则本用例测不到目标窗口");
            // ③ 此时批准发放：必须读到真实状态并拒绝
            assertThrows(BusinessException.class, () -> medalGrantService.approve(grantId, AUDITOR),
                    "勋章已被停用，这次发放不该还能生效");
            // approve 抛异常会把当前事务标记为 rollback-only，显式回滚避免提交时报 UnexpectedRollbackException
            status.setRollbackOnly();
        });

        HonorMedalGrant grant = grantMapper.selectById(grantId);
        assertEquals(MedalGrantStatus.PENDING, grant.getStatus(), "发放应仍停在待审核");
        assertNull(grant.getReviewTime(), "不该留下审核痕迹");
    }

    /**
     * 发放审核开始后<b>志愿者</b>被禁用，本次审核必须失败——与勋章那侧同一形状的窗口。
     *
     * <p><b>为什么顺序用例不够</b>：{@code MedalGrantServiceTest} 里那两条是「先提交禁用、
     * 再调 approve」，两者之间没有窗口——即使把 {@code selectByIdForShare} 换回普通
     * {@code selectById}，那时事务尚未开始、读视图还没定死，照样能读到最新状态并拒绝。
     * 承重的变异是「当前读 → 快照读」，只有本用例能压住：在事务里先读一次建立快照，
     * 让另一连接禁用并提交，此后普通查询会一直读到「正常」，唯有 {@code FOR SHARE} 读得到真值。</p>
     */
    @Test
    void approveGrant_seesConcurrentVolunteerBan() {
        Long medal = enabledMedal("审核中禁用");
        Long volunteerId = volunteer("审核中被禁的小徐");
        Long grantId = medalGrantService.apply(grantDto(medal, volunteerId), APPLICANT);

        repeatableRead.executeWithoutResult(status -> {
            // ① 建立快照：志愿者此刻状态正常
            assertEquals(0, volunteerStatusFromDb(volunteerId));
            // ② 另一个连接把他禁用并提交
            commitInOtherThread(() -> {
                Volunteer banned = new Volunteer();
                banned.setId(volunteerId);
                banned.setStatus(1);
                volunteerMapper.updateById(banned);
            });
            // 用例有效性自检：对方已提交，本事务快照仍应读到「正常」
            assertEquals(0, volunteerStatusFromDb(volunteerId),
                    "RR 快照应仍读到正常状态，否则本用例测不到目标窗口");
            // ③ 此时批准发放：必须靠当前读看到真实状态并拒绝
            assertThrows(BusinessException.class, () -> medalGrantService.approve(grantId, AUDITOR),
                    "志愿者已被禁用，这次发放不该还能生效");
            status.setRollbackOnly();
        });

        HonorMedalGrant grant = grantMapper.selectById(grantId);
        assertEquals(MedalGrantStatus.PENDING, grant.getStatus(), "发放应仍停在待审核");
        assertNull(grant.getReviewTime(), "不该留下审核痕迹");
    }

    // ================= ④ 发起发放与删除定义不得交错 =================

    /**
     * <b>S/X 锁确实互斥</b>：{@code apply} 读到勋章之后、发放记录提交之前，{@code delete} 必须被挡住。
     *
     * <p><b>为什么需要这条而不是只有下面那条顺序用例</b>：
     * {@link #apply_failsWhenMedalDeletedConcurrently} 里删除事务是<b>先完整提交</b>、
     * 之后才调 {@code apply} 的。那种时序下即使把共享锁完全去掉，只要发起侧做一次最新读
     * 也一样会发现「已删除」而拒绝——它验证的是「当前读不会读到旧行」，
     * 而不是「持锁期间对方会被阻塞」。真正要证明的互斥必须用屏障构造：</p>
     * <ol>
     *   <li>事务 A 调 {@code apply}，内部 {@code FOR SHARE} 取得共享锁并插入发放记录，<b>不提交</b>；</li>
     *   <li>事务 B 开始 {@code delete}，其 {@code FOR UPDATE} 与 A 的共享锁冲突而<b>进入等待</b>——
     *       这一步<b>直接从 {@code performance_schema} 里读出那条等待关系</b>
     *       （{@code honor_medal} 的这一行、等待方要 X、阻塞方持 S，见 {@link #awaitLockWait}），
     *       而不是靠「B 在 N 秒内没跑完」这类计时推断；</li>
     *   <li>A 提交；</li>
     *   <li>B 拿到锁后继续，此时已能看到那条已提交的待审发放记录，于是拒绝删除。</li>
     * </ol>
     *
     * <p><b>变异验证</b>：把 {@code MedalGrantService.apply} 里的 {@code requireMedalForShare}
     * 换回普通 {@code requireMedal}，B 不会被阻塞 → 在 A 提交前就删掉勋章 → A 随后提交，
     * 留下一条指向已删勋章的孤儿记录，本用例的第 2 步与末尾断言都会红。</p>
     */
    @Test
    void delete_blocksUntilApplyCommits_thenRefuses() throws Exception {
        Long medal = enabledMedal("锁互斥");
        Long volunteer = volunteer("锁互斥小赵");

        CountDownLatch applied = new CountDownLatch(1);      // A：已持共享锁且已插入（未提交）
        CountDownLatch deleteStarted = new CountDownLatch(1); // B：已真正走到 delete 调用点
        CountDownLatch releaseA = new CountDownLatch(1);      // 主线程确认 B 在等锁后，放行 A 提交
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> fa = pool.submit(() -> transactionTemplate.execute(st -> {
                // apply 内部自己取 FOR SHARE；这里不替它加锁，才能让「去掉锁」的变异暴露出来
                Long gid = medalGrantService.apply(grantDto(medal, volunteer), APPLICANT);
                applied.countDown();
                await(releaseA);
                return gid;   // 返回后事务提交
            }));

            Future<Throwable> fb = pool.submit(() -> {
                await(applied);
                deleteStarted.countDown();
                try {
                    medalService.delete(medal);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });

            /* 判据是**直接观测到这枚勋章那一行上的 S↔X 等待关系**，不是「B 在 N 秒内没跑完」。
               后者只是「没完成」的一个可能解释：线程没被调度、连接池阻塞、GC 停顿都能产生
               同样的现象，共享锁其实没生效时用例照样会绿。也不是「实例上有任何锁等待」——
               data_lock_waits 是全实例的，撞上一次无关等待同样会误绿，所以按表、行、锁模式收窄。
               deleteStarted 只用于保证 B 已经出发（省得在它还没开始时就白等）。 */
            await(deleteStarted);
            boolean observedWaiting = awaitLockWait(10_000, medal);
            releaseA.countDown();

            Long grantId = fa.get(30, TimeUnit.SECONDS);
            Throwable deleteError = fb.get(30, TimeUnit.SECONDS);

            assertTrue(observedWaiting,
                    "删除事务应在这枚勋章那一行上等 apply 持有的共享锁；"
                            + "没观测到该行的 S↔X 等待，说明 apply 根本没持锁，互斥不成立");
            assertNotNull(deleteError, "A 已插入待审发放并提交，删除必须失败");
            assertEquals(BusinessException.class, deleteError.getClass(),
                    "应是业务异常「已有发放记录，不能删除」，而不是锁超时/死锁");
            assertNotNull(medalMapper.selectById(medal), "勋章不该被删掉");
            assertEquals(MedalGrantStatus.PENDING, grantMapper.selectById(grantId).getStatus(),
                    "发放记录应正常留存，不该被误删或回滚");
        } finally {
            releaseA.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * 发起发放与删除勋章并发时，不得产生指向已删勋章的<b>孤儿发放记录</b>。
     *
     * <p>缺陷形态：{@code delete} 是「查有没有在用 → 逻辑删除」，{@code apply} 是
     * 「查是否启用 → 插入发放记录」，两段都没有锁。交错时可以删完再插进一条指向它的记录，
     * V28 没有外键、数据库不会拦，志愿者的「我的勋章」就会出现一个查不到定义的空白项。
     * 修复是发起侧取共享锁、删除侧取排他锁。</p>
     *
     * <p>本条覆盖的是<b>「删除已提交，发起侧必须读到最新行」</b>这一半；
     * 「持锁期间对方被阻塞」那一半在 {@link #delete_blocksUntilApplyCommits_thenRefuses}。
     * 两条缺一不可——单独看本条，去掉共享锁也能通过。</p>
     */
    @Test
    void apply_failsWhenMedalDeletedConcurrently() {
        Long medal = enabledMedal("发起时被删");
        Long volunteer = volunteer("孤儿记录小何");

        repeatableRead.executeWithoutResult(status -> {
            assertEquals(1, countFromDb(medal));
            // 另一个连接把这枚勋章删了（此时还没有任何发放记录，删除是允许的）
            commitInOtherThread(() -> medalService.delete(medal));
            assertEquals(1, countFromDb(medal),
                    "RR 快照应仍能读到这枚勋章，否则本用例测不到目标窗口");

            assertThrows(BusinessException.class,
                    () -> medalGrantService.apply(grantDto(medal, volunteer), APPLICANT),
                    "勋章已被删除，不该还能发起发放");
            status.setRollbackOnly();
        });

        assertEquals(0L, grantMapper.selectCount(Wrappers.<HonorMedalGrant>lambdaQuery()
                        .eq(HonorMedalGrant::getMedalId, medal)),
                "不该留下指向已删勋章的孤儿发放记录");
    }

    // ---------- helpers ----------

    /**
     * 直读状态，<b>绕开 MyBatis 一级缓存</b>。
     *
     * <p>{@code JdbcTemplate} 经 {@code DataSourceUtils} 取当前事务的连接，所以读视图与
     * Mapper 完全一致（同一事务、同一 RR 快照），区别只在于它每次都真的发一条 SQL。
     * 用 Mapper 做「两次读同一行」的自检时，第二次会命中 SESSION 级一级缓存直接返回上次的对象，
     * 断言便退化成「缓存有效」，与隔离级别无关。</p>
     */
    private Integer statusFromDb(Long medalId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM honor_medal WHERE id = ? AND is_deleted = 0", Integer.class, medalId);
    }

    /** 直读志愿者账号状态，同样绕开一级缓存（见 {@link #statusFromDb}）。 */
    private Integer volunteerStatusFromDb(Long volunteerId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM volunteer WHERE id = ? AND is_deleted = 0", Integer.class, volunteerId);
    }

    /** 直读该勋章是否仍存在（未逻辑删除），同样绕开一级缓存。 */
    private Integer countFromDb(Long medalId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM honor_medal WHERE id = ? AND is_deleted = 0", Integer.class, medalId);
    }

    /**
     * 等待并确认 InnoDB 中<b>确实出现了目标那一条行锁等待关系</b>——
     * 即「有人要 {@code honor_medal} 这一行的排他锁，被另一个持有该行共享锁的事务挡住」。
     * 这是「B 被 A 的 {@code FOR SHARE} 挡住」的直接证据。
     *
     * <p><b>为什么不能只数 {@code data_lock_waits} 的行数</b>：那张表是<b>实例级</b>的，
     * 装的是整台 MySQL 上所有正在发生的锁等待。容器里同时还有别的连接（其它用例的收尾事务、
     * 连接池探活、Flyway 的元数据行……），只要恰好撞上任意一次无关等待，
     * {@code COUNT(*) > 0} 就成立——于是「观测到锁等待」并不蕴含「观测到我要证明的那次互斥」。
     * 判据必须落到具体的表、具体的行、具体的锁模式上：</p>
     * <ul>
     *   <li>等待方（requesting）要的是 {@code honor_medal} 上的 {@code RECORD} 锁，模式 {@code X*}
     *       —— {@code MedalService.delete} 的 {@code selectByIdForUpdate}；</li>
     *   <li>阻塞方（blocking）持有<b>同一行</b>的 {@code S*} —— {@code apply} 的 {@code selectByIdForShare}；</li>
     *   <li>{@code LOCK_DATA} 等于本用例这枚勋章的主键，排除同表其它行上的等待；</li>
     *   <li>{@code OBJECT_SCHEMA} 限定为容器的业务库——{@code OBJECT_NAME} 只是表名，
     *       同实例里另一个库（如日后并行跑的第二套 schema）的同名表会照样匹配；</li>
     *   <li>{@code INDEX_NAME = 'PRIMARY'} —— 行锁是挂在<b>索引</b>上的，
     *       {@code LOCK_DATA} 记的是该索引上的键值。不限定索引的话，某条二级索引上恰好
     *       长得一样的键值（如某个值为该 id 的 {@code BIGINT} 列）也会被算进来。
     *       这里两侧走的都是 {@code WHERE id = ?} 的主键查找，锁必然落在 {@code PRIMARY} 上。</li>
     * </ul>
     * <p>共享锁若被去掉，阻塞方压根不持锁，这条查询恒为 0，本方法返回 false。</p>
     *
     * <p><b>为什么不用「B 在 N 秒内没跑完」来判定</b>：那只是「没完成」的诸多解释之一——
     * 线程没被调度、连接池阻塞、GC 停顿都会产生同样的现象，于是共享锁其实已经失效时用例照样变绿。
     * 一个只在「实现正确」时为真、在实现错误时也可能为真的判据，起不到断言的作用。</p>
     *
     * <p><b>权限</b>：Testcontainers 的业务账号（{@code test}）读不到 performance_schema，
     * 所以这里<b>另开一条 root 连接</b>专门做观测。{@code MySQLContainer} 把 root 口令设成与
     * 业务账号相同，容器 Bean 本身可以从 Spring 上下文注入，无需改动共享的
     * {@code TestcontainersConfig}、也不会给业务账号放开任何权限。</p>
     *
     * @param millis  最长等待
     * @param medalId 必须出现在等待关系里的那一行
     * @return true=观测到目标锁等待
     */
    private boolean awaitLockWait(long millis, Long medalId) throws Exception {
        String sql = "SELECT COUNT(*)"
                + "  FROM performance_schema.data_lock_waits w"
                + "  JOIN performance_schema.data_locks req"
                + "    ON req.ENGINE = w.ENGINE AND req.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID"
                + "  JOIN performance_schema.data_locks blk"
                + "    ON blk.ENGINE = w.ENGINE AND blk.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID"
                + " WHERE req.OBJECT_SCHEMA = ? AND req.OBJECT_NAME = 'honor_medal'"
                + "   AND req.INDEX_NAME = 'PRIMARY' AND req.LOCK_TYPE = 'RECORD'"
                + "   AND req.LOCK_MODE LIKE 'X%' AND req.LOCK_DATA = ?"
                + "   AND blk.OBJECT_SCHEMA = req.OBJECT_SCHEMA AND blk.OBJECT_NAME = 'honor_medal'"
                + "   AND blk.INDEX_NAME = 'PRIMARY' AND blk.LOCK_TYPE = 'RECORD'"
                + "   AND blk.LOCK_MODE LIKE 'S%' AND blk.LOCK_DATA = req.LOCK_DATA";
        long deadline = System.currentTimeMillis() + millis;
        try (Connection conn = DriverManager.getConnection(
                mysqlContainer.getJdbcUrl(), "root", mysqlContainer.getPassword());
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, mysqlContainer.getDatabaseName());
            ps.setString(2, String.valueOf(medalId));
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

    private Long enabledMedal(String name) {
        Long id = medalService.create(dto(name));
        medalService.submit(id);
        medalService.approve(id, AUDITOR);
        return id;
    }

    private MedalSaveDTO dto(String name) {
        MedalSaveDTO dto = new MedalSaveDTO();
        dto.setName(name + "_" + SEQ.incrementAndGet());
        dto.setIconUrl("https://example.com/medal.png");
        dto.setConditionType(MedalConditionType.MANUAL);
        dto.setRewardPoints(0);
        dto.setSort(0);
        return dto;
    }

    private MedalGrantDTO grantDto(Long medalId, Long volunteerId) {
        MedalGrantDTO dto = new MedalGrantDTO();
        dto.setMedalId(medalId);
        dto.setVolunteerId(volunteerId);
        dto.setReason("表现优异");
        return dto;
    }

    private Long volunteer(String realName) {
        Volunteer v = new Volunteer();
        v.setOpenid("iso:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setStatus(0);
        v.setRealName(realName);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    /** 在另一个线程（另一条连接、独立事务）里跑完并提交，制造「本事务快照之后的已提交改动」。 */
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
}
