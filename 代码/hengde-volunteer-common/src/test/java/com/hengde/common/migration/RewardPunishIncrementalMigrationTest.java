package com.hengde.common.migration;

import com.hengde.common.testsupport.TestcontainersConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 奖惩批次（V32 → V36）的<b>真实升级测试</b>，重点是<b>失败路径</b>与<b>版本切分</b>。
 *
 * <p><b>为什么必须专门测</b>：其余用例都是「空库一路迁到最新」，那条路上
 * {@code honor_reward_punish} 一行数据都没有，新约束必然加得上——它们证明的只是终态正确，
 * 完全没有碰过「存量里就有 {@code sanction_days > 3650}」这个真实升级场景。
 * 而那正是这批迁移唯一可能失败的地方。</p>
 *
 * <p><b>本类要钉死的三件事</b>：</p>
 * <ol>
 *   <li><b>失败要「什么都没发生」</b>——脏数据导致 V33 失败时 V32 的旧约束还在、
 *       列也没被改，清完数据 {@code repair} + 重跑就能继续。
 *       早先的写法是「先 {@code DROP CHECK} 旧的、再 {@code ADD} 新的」两条 DDL，各自隐式提交：
 *       DROP 成功提交、ADD 校验失败 ⇒ 迁移失败<b>而且旧约束已经没了</b>，
 *       重跑时 DROP 又因约束不存在再失败一次，库卡死。</li>
 *   <li><b>排序规则变更必须在 V33 之外</b>（{@link #v33StopsShortOfTheCollationChange}）——
 *       这两条曾经同处一个文件，而它们的失败条件互不相干：
 *       {@code utf8mb4_0900_bin} 是 <b>MySQL 8.0.17</b> 才有的排序规则，
 *       在 8.0.16 上第 1 条会成功并隐式提交、第 2 条报 {@code ERROR 1273 Unknown collation}，
 *       升级后 {@code repair} 重跑第 1 条再报 {@code ERROR 3822 Duplicate check constraint name}。
 *       （这三步已在 {@code mysql:8.0.16} 容器上实测过。）
 *       把它们拆成两个版本，任一失败都只回到自己那一版的起点。</li>
 *   <li><b>V35 之后驳回不再占位</b>（{@link #rejectedTicketNoLongerHoldsTheViolationKey}）。</li>
 * </ol>
 *
 * <p><b>需本机有 Docker。</b>容器版本由 {@link TestcontainersConfig#IMAGE} 钉死。</p>
 *
 * @author hengde
 */
class RewardPunishIncrementalMigrationTest {

    private static final String MIGRATIONS = "classpath:db/migration";

    /** V32 建表时列跟随服务器默认排序规则；V34 才改成二进制 */
    private static final String DEFAULT_COLLATION = "utf8mb4_0900_ai_ci";
    private static final String BINARY_COLLATION = "utf8mb4_0900_bin";

    private static MySQLContainer<?> mysql;

    @BeforeAll
    static void startContainer() {
        mysql = new MySQLContainer<>(DockerImageName.parse(TestcontainersConfig.IMAGE));
        mysql.start();
    }

    @AfterAll
    static void stopContainer() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @BeforeEach
    void reset() {
        cleanDatabase();
    }

    /**
     * 存量存在超限行时：V33 必须失败，且失败之后 V32 的旧约束仍然有效、列也没被改。
     * 清掉脏数据后重跑必须能一路走到底。
     */
    @Test
    void dirtyData_failsWithoutLosingTheOldConstraint() throws SQLException {
        migrateTo("32");
        // V32 的 ck_rp_sanction_days 只要求 > 0，5000 天在它眼里完全合法——这正是存量脏数据的来源
        insertPunish("V33DIRTY", 5000);

        // ① V33 必须失败
        assertThrows(Exception.class, () -> migrateTo("33"),
                "存量有 sanction_days > 3650 时，加上限约束必须失败");

        // ② 失败之后旧约束仍在：0 天照样插不进去
        assertThrows(SQLException.class, () -> insertPunish("V33OLDCK", 0),
                "V32 的 ck_rp_sanction_days(> 0) 必须还活着——迁移失败不该把已有防线一起带走");

        // ③ 新约束不该已经存在（否则 repair 重跑会撞约束重名）
        assertFalse(checkExists("ck_rp_sanction_days_cap"),
                "失败的那一版不该留下半个约束");

        // ④ 清掉脏数据后，repair + 重跑必须能走到最新
        deleteByNo("V33DIRTY");
        repair();
        migrateToLatest();
        assertEquals(BINARY_COLLATION, requestIdCollation(), "重跑后 V34 的列变更应生效");
        assertThrows(SQLException.class, () -> insertPunish("V33CAP", 3651),
                "新上限约束应已生效");
        insertPunish("V33OK", 3650);   // 边界值合法，不抛即通过
    }

    /**
     * <b>V33 只做约束、不碰排序规则</b>——两者必须分属不同版本。
     *
     * <p>把 V34 的 {@code ALTER TABLE point_record ...} 挪回 V33，本用例立刻变红。
     * 它守的是「一个迁移文件只放一条有失败可能的语句」这条纪律，而不是某个终态。</p>
     */
    @Test
    void v33StopsShortOfTheCollationChange() throws SQLException {
        migrateTo("33");

        assertTrue(checkExists("ck_rp_sanction_days_cap"), "V33 应当已经加上上限约束");
        assertEquals(DEFAULT_COLLATION, requestIdCollation(),
                "排序规则变更属于 V34；与约束同处一个版本时，两条语句的失败会互相拖累");

        migrateTo("34");
        assertEquals(BINARY_COLLATION, requestIdCollation(), "V34 才改列");
    }

    /**
     * V35：被<b>驳回</b>的单不再占住「一条违规最多一张单」的位置。
     *
     * <p>把 V35 的生成列表达式改回 {@code CASE WHEN is_deleted = 0 ...}，第二步必红。</p>
     */
    @Test
    void rejectedTicketNoLongerHoldsTheViolationKey() throws SQLException {
        migrateTo("34");
        insertPunishForViolation("V35REJ", 8801, 2);      // 已驳回
        assertThrows(SQLException.class, () -> insertPunishForViolation("V35BLOCKED", 8801, 0),
                "V34 及以前：驳回的单永久占位，同一条违规再也开不出单——这正是缺陷本身");

        migrateTo("35");

        insertPunishForViolation("V35REOPEN", 8801, 0);   // 驳回之后重开，不抛即通过
        assertThrows(SQLException.class, () -> insertPunishForViolation("V35DUP", 8801, 0),
                "同一条违规仍然只能有一张【未被驳回】的单");
    }

    /**
     * 钉住 {@link TestcontainersConfig#MIN_SUPPORTED} 这个声明：<b>容器实际跑的版本必须够格，
     * 而且够格的理由必须成立</b>。
     *
     * <p>没有这一条时 {@code MIN_SUPPORTED} 只是一句注释——把 {@code IMAGE} 换成
     * {@code mysql:8.0.16}，整套迁移用例会以一堆莫名其妙的方式失败，而没有任何一条会说出
     * 「版本不够」这四个字。这里顺带断言 {@code utf8mb4_0900_bin} 真的存在：
     * 它<b>正是</b>把门槛从 8.0.16 抬到 8.0.17 的那条理由，只比版本号不比能力就是在比一个代号。</p>
     */
    @Test
    void containerMeetsTheDeclaredVersionFloor() throws SQLException {
        String actual = scalar("SELECT VERSION()");
        assertTrue(compareVersion(actual, TestcontainersConfig.MIN_SUPPORTED) >= 0,
                "测试容器 " + TestcontainersConfig.IMAGE + " 实际版本 " + actual
                        + " 低于项目声明的最低版本 " + TestcontainersConfig.MIN_SUPPORTED);
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.COLLATIONS"
                        + " WHERE COLLATION_NAME = '" + BINARY_COLLATION + "'"),
                BINARY_COLLATION + " 必须存在——它是 8.0.17 才有的排序规则，"
                        + "也正是最低版本从 8.0.16 抬到 8.0.17 的原因（V34 靠它）");
    }

    /** 干净存量的正常路径：V32 → 最新一次过，两条约束并存、权限点齐备。 */
    @Test
    void cleanData_upgradesAndKeepsBothConstraints() throws SQLException {
        migrateTo("32");
        insertPunish("V33CLEAN", 30);

        migrateToLatest();

        assertEquals(BINARY_COLLATION, requestIdCollation());
        assertTrue(checkExists("ck_rp_sanction_days"), "V32 的下界约束应保留");
        assertTrue(checkExists("ck_rp_sanction_days_cap"), "V33 的上限约束应加上");
        assertTrue(permissionExists("honor:sanction-all"), "V36 的「全部限制能力」权限点应已预置");
        assertFalse(hasRow("V33MISSING"), "夹具自检：不存在的编号不该被查到");
        assertThrows(SQLException.class, () -> insertPunish("V33TOOBIG", 99999));
        assertThrows(SQLException.class, () -> insertPunish("V33ZERO", 0));
    }

    // ---------- 夹具 ----------

    /** type=2（处罚）+ 能力域 1，满足 V32 的另外两条 CHECK。 */
    private void insertPunish(String no, int days) throws SQLException {
        try (Connection conn = open();
             Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO honor_reward_punish"
                    + " (rp_no, volunteer_id, type, category, points_delta, sanction_scope, sanction_days)"
                    + " VALUES ('" + no + "', 960001, 2, 'V33 增量用例', 0, 1, " + days + ")");
        }
    }

    /** 关联某条违规的处罚单，{@code reviewStatus} 0待审核/1已通过/2已驳回。 */
    private void insertPunishForViolation(String no, long violationId, int reviewStatus)
            throws SQLException {
        try (Connection conn = open();
             Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO honor_reward_punish"
                    + " (rp_no, volunteer_id, type, category, points_delta, violation_id, review_status)"
                    + " VALUES ('" + no + "', 960002, 2, 'V35 增量用例', 0, "
                    + violationId + ", " + reviewStatus + ")");
        }
    }

    private void deleteByNo(String no) throws SQLException {
        try (Connection conn = open();
             Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM honor_reward_punish WHERE rp_no = '" + no + "'");
        }
    }

    private boolean hasRow(String no) throws SQLException {
        return count("SELECT COUNT(*) FROM honor_reward_punish WHERE rp_no = '" + no + "'") > 0;
    }

    private boolean permissionExists(String code) throws SQLException {
        return count("SELECT COUNT(*) FROM permission WHERE code = '" + code + "'") > 0;
    }

    private boolean checkExists(String name) throws SQLException {
        return count("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS"
                + " WHERE CONSTRAINT_SCHEMA = DATABASE()"
                + " AND TABLE_NAME = 'honor_reward_punish'"
                + " AND CONSTRAINT_NAME = '" + name + "'") > 0;
    }

    private String requestIdCollation() throws SQLException {
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COLLATION_NAME FROM information_schema.COLUMNS"
                             + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'point_record'"
                             + " AND COLUMN_NAME = 'request_id'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private String scalar(String sql) throws SQLException {
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** 逐段比较 {@code 8.0.46-log} 这类版本串的前三段；缺省段按 0 处理。 */
    private static int compareVersion(String a, String b) {
        int[] x = parseVersion(a);
        int[] y = parseVersion(b);
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) {
                return Integer.compare(x[i], y[i]);
            }
        }
        return 0;
    }

    private static int[] parseVersion(String v) {
        int[] out = new int[3];
        String[] parts = v.trim().split("[.\\-+ ]");
        for (int i = 0; i < 3 && i < parts.length && parts[i].matches("\\d+"); i++) {
            out[i] = Integer.parseInt(parts[i]);
        }
        return out;
    }

    private int count(String sql) throws SQLException {
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void migrateTo(String version) {
        flyway(version).migrate();
    }

    private void migrateToLatest() {
        flyway(null).migrate();
    }

    /** 迁移失败会在 schema history 里留下一条 success=0 的行，重跑前必须先 repair 清掉。 */
    private void repair() {
        flyway(null).repair();
    }

    private void cleanDatabase() {
        flyway(null).clean();
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations(MIGRATIONS)
                .cleanDisabled(false);
        if (target != null) {
            config = config.target(target);
        }
        return config.load();
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }
}
