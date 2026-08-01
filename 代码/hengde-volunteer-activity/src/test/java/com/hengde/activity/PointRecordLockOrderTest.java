package com.hengde.activity;

import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.dao.PointRecordMapper;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「重复键复核」语句的<b>确定性锁序测试</b>：复核不得要求排他锁。
 *
 * <p><b>为什么单独写这一个类</b>：{@code PointServiceTest} 里走服务层的三线程用例只能把屏障放在
 * 「三个快照已建立、即将调用 record()」处，<b>不能保证</b>它们真的同时停在「即将发起复核读」那一刻——
 * 调度稍有偏差就测不到锁冲突。那个用例负责验证<b>服务控制流</b>（重放不抛异常、只入账一次），
 * 锁序结论由本类保证。</p>
 *
 * <p><b>本类如何做到确定性</b>：让两个事务先各自对同一行取<b>共享锁</b>并在屏障处会合，
 * 确认双方都持有 S 锁之后，再同时执行复核语句：</p>
 * <ul>
 *   <li>复核若是 {@code FOR SHARE} —— S 与 S 相容，两者都能通过；</li>
 *   <li>复核若是 {@code FOR UPDATE} —— 双方都需把 S 升级为 X，而 X 要等对方放掉 S，
 *       互等成环，MySQL 立刻判 {@code ER_LOCK_DEADLOCK(1213)}。这是<b>必然</b>发生的，不靠调度运气。</li>
 * </ul>
 *
 * <p><b>本类覆盖两套唯一键</b>：{@code uk_source} 与 {@code uk_request_id} 各自对应一条复核语句
 * （{@link PointRecordMapper#selectBySourceForShare}、{@link PointRecordMapper#selectByRequestIdForShare}）。
 * 两条都得测——只测其一时，另一条单独被改回 {@code FOR UPDATE} 仍会全绿。</p>
 *
 * <p><b>为什么用显式 {@code FOR SHARE} 制造锁状态，而不用「撞唯一键的 INSERT」</b>——
 * 这里曾写过一条<b>错误</b>结论，一并记下以免重蹈：原注释称「RR 下失败的 INSERT 拿的是共享 next-key 锁，
 * 后续重复 INSERT 需要该间隙的 insert-intention 锁而被阻塞，故『多方同时持有由重复键产生的 S 锁』
 * 这个状态构造不出来」。<b>后半句是错的</b>，按 {@code performance_schema.data_locks} 实测：</p>
 * <ul>
 *   <li>赢家先 INSERT 且<b>暂不提交</b>时，三个 loser 在 {@code uk_source S} 上排队，
 *       赢家一提交，三个 {@code S} <b>同时 GRANTED</b>——这正是生产并发穿透的真实时序，
 *       与 MySQL 8.0 手册 <i>Locks Set by Different SQL Statements in InnoDB</i> 一致；</li>
 *   <li>当初观察到的「重复 INSERT 互相阻塞」来自另一种时序（目标行<b>预先提交</b>），
 *       且等待点是 {@code PRIMARY} supremum 上的 {@code X,INSERT_INTENTION}
 *       ——撞键失败的前一个 INSERT 在 supremum 留下了间隙锁——<b>不是</b> {@code uk_source} 的 S；
 *       S 与 S（含间隙部分）本就相容。</li>
 * </ul>
 *
 * <p>教训是「<b>并发被串行了</b>」不等于「<b>这两把锁不相容</b>」，得把 {@code data_locks} 打出来看
 * 等待点落在哪个索引上。故 {@link PointRecordMapper} / {@code PointService} 里
 * 「多个 loser 各持 S、再抢 X 会互等成死锁」的说明<b>是对的</b>，{@code FOR SHARE} 有真实时序支撑。
 * 本类仍选显式取锁，只是因为复现真实时序要轮询 {@code performance_schema.data_locks} 才能判定
 * 「三方都已进入等待」，还得给测试账号补授该表的 {@code SELECT}（Testcontainers 默认账号只授了 {@code test} 库，
 * 故当初的探针改用了 root——<b>只需该表的普通 {@code SELECT}，不需要 {@code PROCESS}</b>，
 * 要 PROCESS 的是 5.7 时代的 {@code INFORMATION_SCHEMA.INNODB_LOCKS}），且轮询与屏障的实现本身要几十秒。
 * 显式取锁则能把「双方已持 S」变成屏障处的<b>先决条件</b>——锁状态等价，且不依赖 InnoDB 内部时序。</p>
 *
 * <p>复核语句<b>直接从 {@link PointRecordMapper} 的 {@code @Select} 注解反射取出</b>，不在测试里另抄一份——
 * 否则只能证明「FOR SHARE 不死锁」这条 MySQL 性质，而生产代码被改回 {@code FOR UPDATE} 时它照样绿。
 * 显式取锁语句的 where 也来自同一份 {@link KeyKind#where}，并断言与复核语句一致，
 * 免得两者锁到不同索引记录、测试变成自说自话。</p>
 *
 * <p>MySQL + Redis 由 Testcontainers 起。<b>需本机有 Docker。</b></p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class PointRecordLockOrderTest {

    /** 两方即可构成 S→X 升级环；参与方越多只会让失败更快，不影响确定性 */
    private static final int HOLDERS = 2;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);

    @Autowired
    private DataSource dataSource;

    @ParameterizedTest(name = "{0}")
    @EnumSource(KeyKind.class)
    void recheckStatement_mustNotRequireExclusiveLock(KeyKind keyKind) throws Exception {
        Binder bind = keyKind.seed(dataSource, SEQ.incrementAndGet());

        String recheckSql = toJdbcSql(mapperSql(keyKind.mapperMethod, keyKind.paramTypes));
        assertTrue(recheckSql.toUpperCase().contains(" FOR "),
                "复核必须是当前读，否则 RR 快照读不到并发已提交的冲突行：" + recheckSql);
        assertTrue(recheckSql.contains(keyKind.where),
                "复核语句的检索条件已变，本用例的显式取锁会锁到别的记录上，断言随之失真：" + recheckSql);

        String sharedLockSql = "SELECT id FROM point_record WHERE " + keyKind.where + " FOR SHARE";
        CyclicBarrier bothHoldSharedLock = new CyclicBarrier(HOLDERS);
        ExecutorService pool = Executors.newFixedThreadPool(HOLDERS);
        List<Future<Long>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < HOLDERS; i++) {
                futures.add(pool.submit(() -> {
                    try (Connection conn = dataSource.getConnection()) {
                        conn.setAutoCommit(false);
                        // ① 取共享锁——两个事务都能拿到，S 与 S 相容
                        assertNotNull(queryId(conn, sharedLockSql, bind), "应锁到目标行");
                        // ② 会合：此刻双方确实都持有该行的 S 锁
                        bothHoldSharedLock.await(30, TimeUnit.SECONDS);
                        // ③ 同时执行生产的复核语句。要 X 锁就会在这里死锁
                        Long found = queryId(conn, recheckSql, bind);
                        conn.commit();
                        return found;
                    }
                }));
            }
            for (Future<Long> f : futures) {
                assertNotNull(f.get(60, TimeUnit.SECONDS),
                        "复核应能读到冲突行；若抛 DeadlockLoser/1213，说明复核语句要了排他锁");
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, countRows(keyKind.where, bind), "复核是只读的，不应改动数据");
    }

    // ---------- 两套唯一键 ----------

    /** 给 {@link PreparedStatement} 绑上本次用例的键值（顺序与 mapper 语句的占位符一致）。 */
    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    /**
     * 一套唯一键 = 一条复核语句 + 一份检索条件 + 一种造行方式；其余流程两者共用。
     *
     * <p>{@code where} 同时用于「显式取锁」「计数」两条语句，并与反射取出的复核语句比对，
     * 保证三条语句锁/查的是同一条索引记录。</p>
     */
    private enum KeyKind {

        /** 有单据来源（活动积分等）：靠 {@code uk_source(source_type, source_id)} 防重。 */
        UK_SOURCE("uk_source（有单据来源）",
                "selectBySourceForShare", new Class<?>[]{int.class, Long.class},
                "source_type = ? AND source_id = ?") {
            @Override
            Binder seed(DataSource ds, long seq) throws SQLException {
                long sourceId = 770_000L + seq;
                insertRow(ds, 990_000L + seq, PointSourceType.ACTIVITY, sourceId, null);
                return ps -> {
                    ps.setInt(1, PointSourceType.ACTIVITY);
                    ps.setLong(2, sourceId);
                };
            }
        },

        /**
         * 管理员手工调整：{@code source_id} 恒为 NULL、{@code uk_source} 对它形同虚设，
         * 幂等改由 {@code uk_request_id} 兜。复核走的是另一条语句，故必须单独测。
         */
        UK_REQUEST_ID("uk_request_id（管理员手工调整）",
                "selectByRequestIdForShare", new Class<?>[]{String.class},
                "request_id = ?") {
            @Override
            Binder seed(DataSource ds, long seq) throws SQLException {
                String requestId = "lock-order-" + seq;
                insertRow(ds, 990_000L + seq, PointSourceType.MANUAL, null, requestId);
                return ps -> ps.setString(1, requestId);
            }
        };

        private final String label;
        private final String mapperMethod;
        private final Class<?>[] paramTypes;
        private final String where;

        KeyKind(String label, String mapperMethod, Class<?>[] paramTypes, String where) {
            this.label = label;
            this.mapperMethod = mapperMethod;
            this.paramTypes = paramTypes;
            this.where = where;
        }

        /** 造一行只属于本次运行的目标行，返回配套的绑参器。 */
        abstract Binder seed(DataSource ds, long seq) throws SQLException;

        @Override
        public String toString() {
            return label;
        }
    }

    // ---------- helpers ----------

    /** 取 mapper 上 {@code @Select} 的原始 SQL——测试与生产共用同一份语句，改坏了这里必红。 */
    private String mapperSql(String method, Class<?>... paramTypes) throws NoSuchMethodException {
        Select select = PointRecordMapper.class.getMethod(method, paramTypes).getAnnotation(Select.class);
        assertNotNull(select, method + " 应带 @Select 注解");
        return select.value()[0];
    }

    /** MyBatis 的 {@code #{x}} 占位换成 JDBC 的 {@code ?}（按出现顺序绑参）。 */
    private String toJdbcSql(String mybatisSql) {
        return mybatisSql.replaceAll("#\\{\\w+}", "?");
    }

    private static void insertRow(DataSource ds, long volunteerId, int sourceType,
                                  Long sourceId, String requestId) throws SQLException {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO point_record (volunteer_id, change_amount, source_type, source_id, "
                             + "request_id, remark, operator_type, create_time, update_time, is_deleted) "
                             + "VALUES (?, 5, ?, ?, ?, '锁序用例', 0, NOW(), NOW(), 0)")) {
            ps.setLong(1, volunteerId);
            ps.setInt(2, sourceType);
            if (sourceId == null) {
                ps.setNull(3, Types.BIGINT);
            } else {
                ps.setLong(3, sourceId);
            }
            ps.setString(4, requestId);
            ps.executeUpdate();
        }
    }

    private Long queryId(Connection conn, String sql, Binder bind) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("id") : null;
            }
        }
    }

    private int countRows(String where, Binder bind) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM point_record WHERE " + where)) {
            bind.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
