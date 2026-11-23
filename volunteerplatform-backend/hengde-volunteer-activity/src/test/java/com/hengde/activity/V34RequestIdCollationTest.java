package com.hengde.activity;

import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V34：{@code point_record.request_id} 必须是<b>二进制 + NO PAD 排序规则</b>。
 *
 * <p><b>要防的缺陷</b>：申诉成立时的反向流水用 {@code sys:rp-revert:{单据 id}} 作幂等键，
 * {@code PointService} 靠「前缀系统保留」拒绝手工调整占用它。但守卫在 Java 里比字符串、
 * 唯一键在数据库里比排序规则——MySQL 8 默认的 {@code utf8mb4_0900_ai_ci}
 * <b>既忽略大小写、又忽略重音</b>，于是 {@code sýs:rp-revert:1} 在 Java 眼里是另一个键、
 * 在库里却与保留键相等：它过得了守卫，又占住了那张单的冲正位。
 * 等到那张单申诉成立，反向流水撞上这条已存在的键，载荷复核发现来源码不同而抛「积分入账冲突」，
 * <b>整个受理事务回滚</b>——处置没解除、分没退、申诉也办不成，直到有人手工清掉那条占位流水。</p>
 *
 * <p><b>为什么必须走原生 JDBC</b>：服务层的字符集守卫（{@code REQUEST_ID_PATTERN}）会先把
 * 这类键挡在门外，从 {@code PointService} 根本喂不到数据库。而本用例要证明的恰恰是
 * <b>「就算绕过服务层，数据库自己也认得出这是两个键」</b>——两道防线要分开验，
 * 否则删掉 V34 只剩 Java 守卫时，用例照样全绿。</p>
 *
 * <p><b>变异验证（各条覆盖的不是同一件事，别一并声称「全红」）</b>：</p>
 * <ul>
 *   <li>删掉 V34 迁移（列退回默认 {@code utf8mb4_0900_ai_ci}）→
 *       {@link #requestIdColumn_usesBinaryNoPadCollation} 与
 *       {@link #accentVariantIsADistinctKey_atDatabaseLevel} 变红；
 *       <b>{@link #trailingSpaceVariantIsADistinctKey_atDatabaseLevel} 仍是绿的</b>——
 *       默认排序规则本身就是 NO PAD。</li>
 *   <li>把 V34 改成 {@code utf8mb4_bin}（二进制但 <b>PAD SPACE</b>）→ 只有尾空格那条变红。
 *       它守的正是这个方向：别把重音的洞换成尾空格的洞。</li>
 * </ul>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class V34RequestIdCollationTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);

    @Autowired
    private DataSource dataSource;

    /**
     * 直接问 information_schema：排序规则本身就是这条不变量。
     *
     * <p><b>只断言 {@code _bin} 是不够的</b>：{@code utf8mb4_bin} 虽然按码点比较，
     * 但它的 {@code PAD_ATTRIBUTE} 是 <b>PAD SPACE</b>——比较时忽略尾部空格，
     * {@code 'k'} 与 {@code 'k '} 仍是同一个键。而默认的 {@code utf8mb4_0900_ai_ci} 反倒是 NO PAD，
     * 选 {@code utf8mb4_bin} 会在消掉重音洞的同时开出一个尾空格洞，属于倒退。
     * 所以这里连 NO PAD 一起钉住。</p>
     */
    @Test
    void requestIdColumn_usesBinaryNoPadCollation() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT col.COLLATION_NAME, coll.PAD_ATTRIBUTE"
                             + " FROM information_schema.COLUMNS col"
                             + " JOIN information_schema.COLLATIONS coll"
                             + "   ON coll.COLLATION_NAME = col.COLLATION_NAME"
                             + " WHERE col.TABLE_SCHEMA = DATABASE() AND col.TABLE_NAME = 'point_record'"
                             + "   AND col.COLUMN_NAME = 'request_id'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "point_record.request_id 应当存在");
                String collation = rs.getString(1);
                String pad = rs.getString(2);
                assertNotNull(collation, "该列应有排序规则");
                assertTrue(collation.endsWith("_bin"),
                        "幂等键列必须按码点比较，否则库里的『相等』比 Java 宽，"
                                + "系统保留前缀会被重音/大小写变体绕过。实际：" + collation);
                assertEquals("NO PAD", pad,
                        "PAD SPACE 会让尾空格被忽略（'k' = 'k '），等于另开一个绕过口。实际："
                                + collation + " / " + pad);
            }
        }
    }

    /** 尾空格变体也必须是<b>另一个</b>键——这条正是 utf8mb4_bin（PAD SPACE）会失败的地方。 */
    @Test
    void trailingSpaceVariantIsADistinctKey_atDatabaseLevel() throws SQLException {
        long n = System.nanoTime();
        String plain = "sys:rp-revert:" + n;
        String padded = plain + " ";

        try (Connection c = dataSource.getConnection()) {
            insert(c, plain);
            insert(c, padded);

            assertEquals(2, countOf(c, plain, padded),
                    "只差一个尾空格的两个幂等键必须各占一行；只有 1 行说明该列是 PAD SPACE 排序规则");
        }
    }

    /**
     * 行为层面的同一件事：只在重音上不同的两个键，数据库必须视为<b>两个</b>键。
     *
     * <p>在 {@code utf8mb4_0900_ai_ci} 下第二条 INSERT 会撞 {@code uk_request_id} 而失败——
     * 那正是「手工流水抢占冲正键」得以成立的机制。</p>
     */
    @Test
    void accentVariantIsADistinctKey_atDatabaseLevel() throws SQLException {
        // 用 nanoTime 而非自增序号：容器可能跨多次 mvn 运行复用，固定序号会撞上一轮留下的行
        long n = System.nanoTime();
        String plain = "sys:rp-revert:" + n;
        String accented = "sýs:rp-revert:" + n;

        try (Connection c = dataSource.getConnection()) {
            insert(c, plain);
            insert(c, accented);

            assertEquals(2, countOf(c, plain, accented),
                    "两个只差重音的幂等键必须各占一行；只有 1 行说明库里把它们当成了同一个键");
        }
    }

    /** 按 request_id 精确计数——{@code IN} 的比较同样走列排序规则，正是要观察的那件事。 */
    private int countOf(Connection c, String a, String b) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM point_record WHERE request_id IN (?, ?)")) {
            ps.setString(1, a);
            ps.setString(2, b);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void insert(Connection c, String requestId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO point_record (volunteer_id, change_amount, source_type, source_id,"
                        + " request_id, remark, operator_type, operator_id)"
                        + " VALUES (?, 1, 5, NULL, ?, 'V34 排序规则用例', 2, 1)")) {
            ps.setLong(1, 990_000L + SEQ.incrementAndGet());
            ps.setString(2, requestId);
            ps.executeUpdate();
        }
    }
}
