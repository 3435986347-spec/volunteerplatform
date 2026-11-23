package com.hengde.honor;

import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code honor_reward_punish.ck_rp_sanction_days} 这条 CHECK 约束<b>本身</b>的用例。
 *
 * <p><b>为什么不能靠 service 层的用例</b>：{@code RewardPunishServiceTest} 与
 * {@code SanctionService} 的那几条走的是 Java 校验，把 V32/V33 里的 CHECK 整条删掉，
 * 它们照样全绿——约束等于没有覆盖。而这条约束存在的意义恰恰是<b>兜住绕过 service 的写路径</b>：
 * 内部调用、数据修复脚本、下一批新加的入口。只有直接对数据库下语句才测得到它。</p>
 *
 * <p><b>变异验证（各条覆盖的不是同一件事，别一并声称「全红」）</b>：</p>
 * <ul>
 *   <li>删掉 V33 的 {@code ck_rp_sanction_days_cap} → {@link #daysAboveCap_isRejectedByDatabase} 变红。</li>
 *   <li>删掉 V32 的 {@code ck_rp_sanction_days} → {@link #nonPositiveDays_isRejectedByDatabase}
 *       与 {@link #daysWithoutScope_isRejectedByDatabase} 变红。</li>
 *   <li><b>{@link #daysAtCap_isAccepted} 在两种删除下都仍是绿的</b>——它守的是「上界写成 &lt; 而不是 &lt;=」
 *       这个反方向，约束没了自然更放行。它不是覆盖，是防过度收紧。</li>
 * </ul>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, InMemoryFileStorageConfig.class})
class V33SanctionDaysCheckTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);

    @Autowired
    private DataSource dataSource;

    /** V33 加的上限：3650 天以上必须被数据库拒绝，而不是只被 service 拒绝。 */
    @Test
    void daysAboveCap_isRejectedByDatabase() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            SQLException ex = assertThrows(SQLException.class,
                    () -> insert(c, 1, 3651),
                    "3651 天必须撞 ck_rp_sanction_days");
            assertTrue(isCheckViolation(ex), "应当是 CHECK 约束失败，实际：" + ex.getMessage());
        }
    }

    /** 边界本身合法：恰好 3650 天要放行，否则约束写成了 &lt; 而不是 &lt;=。 */
    @Test
    void daysAtCap_isAccepted() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            assertDoesNotThrow(() -> insert(c, 1, 3650), "3650 是允许的上界，不该被拒");
        }
    }

    /** V32 就有的下界：0 与负数无意义。 */
    @Test
    void nonPositiveDays_isRejectedByDatabase() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            assertTrue(isCheckViolation(assertThrows(SQLException.class, () -> insert(c, 1, 0))));
            assertTrue(isCheckViolation(assertThrows(SQLException.class, () -> insert(c, 1, -1))));
        }
    }

    /**
     * 有天数却没有能力域 = 一条「有期限但什么也不限制」的单，
     * 而详情页会照着 sanction_days 印出「限制 7 天」——看着被罚了、实际没有任何约束。
     */
    @Test
    void daysWithoutScope_isRejectedByDatabase() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            SQLException ex = assertThrows(SQLException.class, () -> insert(c, null, 7));
            assertTrue(isCheckViolation(ex), "应当是 CHECK 约束失败，实际：" + ex.getMessage());
        }
    }

    /** MySQL 的 CHECK 约束失败是 SQLSTATE 由 errorCode 3819 报出。 */
    private static boolean isCheckViolation(SQLException ex) {
        return ex.getErrorCode() == 3819
                || (ex.getMessage() != null && ex.getMessage().contains("ck_rp_sanction_days"));
    }

    /** type=2（处罚），因为 ck_rp_sanction_only_punish 要求带处置的单必须是处罚。 */
    private void insert(Connection c, Integer scope, Integer days) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO honor_reward_punish (rp_no, volunteer_id, type, category,"
                        + " points_delta, sanction_scope, sanction_days)"
                        + " VALUES (?, ?, 2, 'V33 约束用例', 0, ?, ?)")) {
            ps.setString(1, "V33CK" + SEQ.incrementAndGet() + "-" + System.nanoTime());
            ps.setLong(2, 970_000L + SEQ.incrementAndGet());
            if (scope == null) {
                ps.setNull(3, Types.TINYINT);
            } else {
                ps.setInt(3, scope);
            }
            if (days == null) {
                ps.setNull(4, Types.INTEGER);
            } else {
                ps.setInt(4, days);
            }
            ps.executeUpdate();
        }
    }
}
