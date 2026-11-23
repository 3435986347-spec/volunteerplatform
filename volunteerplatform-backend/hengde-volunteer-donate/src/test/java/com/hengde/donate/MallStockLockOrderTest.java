package com.hengde.donate;

import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.dao.MallGoodsSpecMapper;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存扣减的<b>确定性锁序测试</b>：扣库存不得走「先读后改」。
 *
 * <p><b>这个类是本批唯一能挡住锁升级死锁的东西</b>，所以它刻意不走服务层——
 * 服务层用例只能把屏障放在「即将调用 placeOrder」处，<b>不能保证</b>两个线程真的同时停在
 * 「已持锁、即将改这一行」那一刻，调度稍有偏差就测不到。本类直接用 JDBC 摆出那个时刻。</p>
 *
 * <p><b>SQL 从 mapper 注解反射取</b>，测试与生产共用同一份定义——否则两边各写一份，
 * 生产那条改坏了测试照样绿（这是本项目反复强调的一条）。</p>
 *
 * <p><b>两个用例是一对，缺一不可</b>：</p>
 * <ul>
 *   <li>{@link #productionPathIsSerialisedWithoutDeadlock()} 证明现在的写法在并发下正确；</li>
 *   <li>{@link #readThenWriteWouldDeadlock()} 证明被否掉的写法（先 {@code FOR SHARE} 再 UPDATE）
 *       <b>必然</b>死锁——即「折进一条 CAS」不是风格偏好，是承重选择。
 *       只有前者时，有人把实现改成先读后改仍会全绿。</li>
 * </ul>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class MallStockLockOrderTest {

    private static final long GOODS_ID = 990_001L;
    private static final long SPEC_ID = 990_101L;

    @Autowired
    private DataSource dataSource;

    /**
     * 生产路径：6 个线程抢 1 件库存 → 恰好 1 个成功，库存落到 0，且<b>无人死锁</b>。
     *
     * <p>结果一律用 {@code Future.get()} 收——catch 掉只看最终库存的话，
     * 6 次里 5 次抛异常也能「通过」。</p>
     */
    @Test
    void productionPathIsSerialisedWithoutDeadlock() throws Exception {
        seedGoods(MallGoodsStatus.ON_SALE, 0, 0, 1);
        String sql = toJdbcSql(mapperSql("deductStock", Long.class, int.class));

        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    try (Connection conn = dataSource.getConnection()) {
                        conn.setAutoCommit(false);
                        barrier.await(20, TimeUnit.SECONDS);
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            ps.setLong(1, SPEC_ID);
                            ps.setInt(2, MallGoodsStatus.ON_SALE);
                            int rows = ps.executeUpdate();
                            conn.commit();
                            return rows;
                        }
                    }
                }));
            }
            int success = 0;
            for (Future<Integer> f : futures) {
                // get() 会把线程内的死锁异常原样抛出来——这正是本用例要盯的
                success += f.get(30, TimeUnit.SECONDS);
            }
            assertEquals(1, success, "1 件库存只能卖出 1 单");
            assertEquals(0, currentStock(), "库存必须落在 0，不得为负");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 被否掉的写法：先 {@code SELECT ... FOR SHARE} 再 UPDATE 同一行 → <b>必然死锁</b>。
     *
     * <p>两个事务先各自对该行取共享锁并在屏障处会合，确认双方都持有 S 之后再同时 UPDATE：
     * 双方都要把 S 升级成 X，而 X 要等对方放掉 S，互等成环，MySQL 立刻判
     * {@code ER_LOCK_DEADLOCK(1213)}。这不靠调度运气。</p>
     *
     * <p>这正是 {@code RewardPunishService} javadoc 里那句「先 S 后 X 是锁升级，
     * 两名管理员同时审同一个人的两张单会直接死锁」在库存上的形态。</p>
     */
    @Test
    void readThenWriteWouldDeadlock() throws Exception {
        seedGoods(MallGoodsStatus.ON_SALE, 0, 0, 10);

        CyclicBarrier bothHoldShared = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    try (Connection conn = dataSource.getConnection()) {
                        conn.setAutoCommit(false);
                        try (PreparedStatement ps = conn.prepareStatement(
                                "SELECT stock FROM mall_goods_spec WHERE id = ? AND is_deleted = 0 FOR SHARE")) {
                            ps.setLong(1, SPEC_ID);
                            try (ResultSet rs = ps.executeQuery()) {
                                assertTrue(rs.next(), "预置的规格应存在");
                            }
                        }
                        bothHoldShared.await(20, TimeUnit.SECONDS);   // 双方都持 S 之后才继续
                        try (PreparedStatement ps = conn.prepareStatement(
                                "UPDATE mall_goods_spec SET stock = stock - 1 WHERE id = ?")) {
                            ps.setLong(1, SPEC_ID);
                            ps.executeUpdate();
                            conn.commit();
                            return "ok";
                        }
                    } catch (SQLException e) {
                        return "SQLSTATE=" + e.getSQLState() + " code=" + e.getErrorCode();
                    }
                }));
            }
            List<String> results = new ArrayList<>();
            for (Future<String> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            boolean deadlocked = results.stream().anyMatch(r -> r.contains("code=1213"));
            assertTrue(deadlocked,
                    "先 FOR SHARE 再 UPDATE 必然死锁（ER_LOCK_DEADLOCK 1213），实际=" + results
                            + "；若这里没红，说明 MySQL 行为变了，D7(b) 的论证要重做");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * {@code s.is_deleted = 0} 是承重条件：软删的规格不得被扣库存。
     *
     * <p>{@code @TableLogic} 只对 MyBatis-Plus 生成的语句与 wrapper 生效，
     * 手写 {@code @Update} 不会自动补——漏掉这个条件，一个已软删的规格照样能下单。</p>
     */
    @Test
    void softDeletedSpecCannotBeDeducted() throws Exception {
        seedGoods(MallGoodsStatus.ON_SALE, 0, 1, 5);
        String sql = toJdbcSql(mapperSql("deductStock", Long.class, int.class));
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, SPEC_ID);
            ps.setInt(2, MallGoodsStatus.ON_SALE);
            assertEquals(0, ps.executeUpdate(), "软删规格不得被扣库存");
        }
        assertEquals(5, currentStock(), "库存不得变化");
    }

    /** 商品被隐藏 / 未上架 / 已软删时，一律扣不动。 */
    @Test
    void hiddenOrUnlistedGoodsCannotBeDeducted() throws Exception {
        String sql = toJdbcSql(mapperSql("deductStock", Long.class, int.class));
        int[][] cases = {
                {MallGoodsStatus.ON_SALE, 1, 0},      // 已隐藏
                {MallGoodsStatus.PENDING, 0, 0},      // 未上架
                {MallGoodsStatus.ON_SALE, 0, 1},      // 商品已软删
        };
        for (int[] c : cases) {
            seedGoodsWith(c[0], c[1], c[2], 0, 5);
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, SPEC_ID);
                ps.setInt(2, MallGoodsStatus.ON_SALE);
                assertEquals(0, ps.executeUpdate(),
                        "status=" + c[0] + " hidden=" + c[1] + " goodsDeleted=" + c[2] + " 时不得扣减");
            }
        }
    }

    // ---------- helpers ----------

    /** 取 mapper 上 {@code @Update} 的原始 SQL——测试与生产共用同一份语句，改坏了这里必红。 */
    private String mapperSql(String method, Class<?>... paramTypes) throws NoSuchMethodException {
        Update update = MallGoodsSpecMapper.class.getMethod(method, paramTypes).getAnnotation(Update.class);
        assertNotNull(update, method + " 应带 @Update 注解");
        return update.value()[0];
    }

    /** MyBatis 的 {@code #{x}} 占位换成 JDBC 的 {@code ?}（按出现顺序绑参）。 */
    private String toJdbcSql(String mybatisSql) {
        return mybatisSql.replaceAll("#\\{\\w+}", "?");
    }

    private void seedGoods(int status, int hidden, int specDeleted, int stock) throws SQLException {
        seedGoodsWith(status, hidden, 0, specDeleted, stock);
    }

    private void seedGoodsWith(int status, int hidden, int goodsDeleted, int specDeleted, int stock)
            throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement del1 = conn.prepareStatement("DELETE FROM mall_goods_spec WHERE id = ?");
             PreparedStatement del2 = conn.prepareStatement("DELETE FROM mall_goods WHERE id = ?");
             PreparedStatement g = conn.prepareStatement(
                     "INSERT INTO mall_goods (id, name, status, hidden, sort, create_time, update_time, is_deleted) "
                             + "VALUES (?, '锁序用例商品', ?, ?, 0, NOW(), NOW(), ?)");
             PreparedStatement s = conn.prepareStatement(
                     "INSERT INTO mall_goods_spec (id, goods_id, name, points, stock, sort, "
                             + "create_time, update_time, is_deleted) VALUES (?, ?, '标准', 10, ?, 0, NOW(), NOW(), ?)")) {
            del1.setLong(1, SPEC_ID);
            del1.executeUpdate();
            del2.setLong(1, GOODS_ID);
            del2.executeUpdate();
            g.setLong(1, GOODS_ID);
            g.setInt(2, status);
            g.setInt(3, hidden);
            g.setInt(4, goodsDeleted);
            g.executeUpdate();
            s.setLong(1, SPEC_ID);
            s.setLong(2, GOODS_ID);
            s.setInt(3, stock);
            s.setInt(4, specDeleted);
            s.executeUpdate();
        }
    }

    private int currentStock() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT stock FROM mall_goods_spec WHERE id = ?")) {
            ps.setLong(1, SPEC_ID);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }
}
