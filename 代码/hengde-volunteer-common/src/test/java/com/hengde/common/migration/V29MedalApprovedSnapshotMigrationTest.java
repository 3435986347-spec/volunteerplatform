package com.hengde.common.migration;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V28 → V29 的<b>真实升级测试</b>：验证「最后过审样式快照」列的补建与<b>存量回填</b>。
 *
 * <p><b>为什么必须专门测</b>：honor 模块的用例走常规 {@code @SpringBootTest}，Flyway 一次性把
 * <b>空库</b>迁到最新，V29 的两条 UPDATE 回填语句实际更新 <b>0 行</b>——那 80 多条用例只证明了
 * 「新代码在审批后会写快照」，完全没有触碰过回填逻辑。而回填面对的恰恰是升级前就已存在的数据，
 * 一旦写错，上线当天所有老勋章的志愿者端展示就是空白或错版。</p>
 *
 * <p><b>覆盖 V29 抬头声明的每一类存量行</b>：</p>
 * <ul>
 *   <li>已启用(2) / 已停用(4)——必然过过审，当前值即过审值，<b>应回填</b>；</li>
 *   <li>待审核(1) / 已驳回(3) 且<b>有生效发放</b>——「曾经过审、被改后退回」的行，
 *       过审版本在 V28 下已被覆盖无法还原，只能用当前值回填（V29 注释已声明这是既成事实）；</li>
 *   <li>草稿(0) / 待审核(1) 且<b>从未发出去过</b>——<b>不该</b>回填，
 *       否则等于给一份从没过审的内容凭空发一张「已过审」的证书；</li>
 *   <li>只有<b>待审/驳回发放</b>或<b>逻辑删除的发放</b>的行——同样不该回填，
 *       那些发放对志愿者不可见，不构成「已经发出去了」。</li>
 * </ul>
 *
 * <p>本类<b>刻意不用 {@code @SpringBootTest}</b>、且<b>放在 common 而非 honor</b>，
 * 理由与同包的 {@link V24BackfillMigrationTest}、{@link V26HonorRankingBatchMigrationTest} 一致：
 * 要自己掌控迁移目标版本，并且永远直接读本模块当前的迁移脚本。</p>
 *
 * <p><b>需本机有 Docker。</b></p>
 *
 * @author hengde
 */
class V29MedalApprovedSnapshotMigrationTest {

    private static final String MIGRATIONS = "classpath:db/migration";

    private static MySQLContainer<?> mysql;

    @BeforeAll
    static void startContainer() {
        mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"));
        mysql.start();
    }

    @AfterAll
    static void stopContainer() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @BeforeEach
    void resetDatabase() {
        cleanDatabase();
    }

    /** V28 不该有快照列——它若已存在，本测试要防的问题就不存在了。 */
    @Test
    void v28_hasNoSnapshotColumns() throws Exception {
        migrateTo("28");
        assertFalse(columnExists("honor_medal", "approved_name"),
                "V28 不该有 approved_name");
    }

    /** V29 应补出全部 6 个快照列。 */
    @Test
    void upgradeFromV28_addsAllSnapshotColumns() throws Exception {
        migrateTo("28");
        migrateTo("29");

        for (String col : new String[]{"approved_name", "approved_icon_url", "approved_description",
                "approved_condition_type", "approved_condition_threshold", "approved_reward_points"}) {
            assertTrue(columnExists("honor_medal", col), "V29 应补出列：" + col);
        }
        assertEquals(29, currentVersion(), "迁移应停在 V29");
    }

    /**
     * 回填的核心：<b>逐类存量行</b>断言。
     *
     * <p>数据在 V28 上灌入（此时还没有快照列），迁到 V29 之后再读，
     * 走的与生产升级完全相同的路径。</p>
     */
    @Test
    void upgradeFromV28_backfillsOnlyApprovedOrGrantedRows() throws Exception {
        migrateTo("28");

        long enabled = insertMedal("已启用", 2, "启用说明");
        long disabled = insertMedal("已停用", 4, null);
        long pendingGranted = insertMedal("待审但发过", 1, "待审说明");
        long rejectedGranted = insertMedal("驳回但发过", 3, "驳回说明");
        long draftNever = insertMedal("草稿从没发过", 0, "草稿说明");
        long pendingNever = insertMedal("待审从没发过", 1, "待审说明2");
        long onlyPendingGrant = insertMedal("只有待审发放", 1, null);
        long onlyDeletedGrant = insertMedal("只有已删发放", 1, null);

        insertGrant(pendingGranted, 1, 1, 0);    // 生效、未删
        insertGrant(rejectedGranted, 2, 1, 0);   // 生效、未删
        insertGrant(onlyPendingGrant, 3, 0, 0);  // 待审 → 不算发出去
        insertGrant(onlyDeletedGrant, 4, 1, 1);  // 生效但逻辑删除 → 不算

        migrateTo("29");

        // ① 已启用 / 已停用：必然过过审，应整体回填
        assertEquals("已启用", approvedName(enabled), "已启用行应回填");
        assertEquals("启用说明", approvedDescription(enabled));
        assertEquals("已停用", approvedName(disabled), "已停用行应回填（停用不收回已发的勋章）");
        assertNull(approvedDescription(disabled), "原本说明为空，回填后也应为空，不该被改写");

        // ② 待审/驳回但确实发出去过：只能用当前值回填，V29 注释已声明这是无法还原的既成事实
        assertEquals("待审但发过", approvedName(pendingGranted), "有生效发放的待审行应回填");
        assertEquals("驳回但发过", approvedName(rejectedGranted), "有生效发放的驳回行应回填");

        // ③ 从未发出去过：不得回填，否则等于给从没过审的内容发「已过审」证书
        assertNull(approvedName(draftNever), "草稿且从未发放，不该有过审快照");
        assertNull(approvedName(pendingNever), "待审且从未发放，不该有过审快照");

        // ④ 只有待审发放 / 只有已逻辑删除的发放：都不算「已经发出去了」
        assertNull(approvedName(onlyPendingGrant),
                "待审发放对志愿者不可见，不构成已发出，不该回填");
        assertNull(approvedName(onlyDeletedGrant),
                "逻辑删除的发放不该被算作生效发放（回填 SQL 必须带 is_deleted = 0）");
    }

    /** 数值型字段也要跟着整体回填，不能只填文本列。 */
    @Test
    void upgradeFromV28_backfillsNumericColumnsToo() throws Exception {
        migrateTo("28");
        long id = insertMedalFull("时长勋章", 2, "说明", 1, 600L, 50);

        migrateTo("29");

        assertEquals(Integer.valueOf(1), approvedInt(id, "approved_condition_type"));
        assertEquals(Long.valueOf(600L), approvedLong(id, "approved_condition_threshold"));
        assertEquals(Integer.valueOf(50), approvedInt(id, "approved_reward_points"));
    }

    // ---------- helpers ----------

    private long insertMedal(String name, int status, String description) throws SQLException {
        return insertMedalFull(name, status, description, 0, null, 0);
    }

    private long insertMedalFull(String name, int status, String description,
                                 int conditionType, Long threshold, int rewardPoints) throws SQLException {
        String sql = "INSERT INTO honor_medal (name, icon_url, description, condition_type,"
                + " condition_threshold, reward_points, sort, status, create_time, update_time, is_deleted)"
                + " VALUES (?, 'https://example.com/i.png', ?, ?, ?, ?, 0, ?, NOW(), NOW(), 0)";
        try (Connection conn = open();
             var ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setInt(3, conditionType);
            if (threshold == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, threshold);
            }
            ps.setInt(5, rewardPoints);
            ps.setInt(6, status);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void insertGrant(long medalId, long volunteerId, int status, int deleted) throws SQLException {
        String sql = "INSERT INTO honor_medal_grant (medal_id, volunteer_id, grant_type, reward_points,"
                + " status, apply_time, create_time, update_time, is_deleted)"
                + " VALUES (?, ?, 1, 0, ?, NOW(), NOW(), NOW(), ?)";
        try (Connection conn = open(); var ps = conn.prepareStatement(sql)) {
            ps.setLong(1, medalId);
            ps.setLong(2, volunteerId);
            ps.setInt(3, status);
            ps.setInt(4, deleted);
            ps.executeUpdate();
        }
    }

    private String approvedName(long id) throws SQLException {
        return approvedString(id, "approved_name");
    }

    private String approvedDescription(long id) throws SQLException {
        return approvedString(id, "approved_description");
    }

    private String approvedString(long id, String col) throws SQLException {
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + col + " FROM honor_medal WHERE id = " + id)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private Integer approvedInt(long id, String col) throws SQLException {
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + col + " FROM honor_medal WHERE id = " + id)) {
            rs.next();
            int v = rs.getInt(1);
            return rs.wasNull() ? null : v;
        }
    }

    private Long approvedLong(long id, String col) throws SQLException {
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + col + " FROM honor_medal WHERE id = " + id)) {
            rs.next();
            long v = rs.getLong(1);
            return rs.wasNull() ? null : v;
        }
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations(MIGRATIONS)
                .target(version)
                .cleanDisabled(false)
                .load()
                .migrate();
    }

    private void cleanDatabase() {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations(MIGRATIONS)
                .cleanDisabled(false)
                .load()
                .clean();
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()"
                             + " AND table_name = '" + table + "' AND column_name = '" + column + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private int currentVersion() throws SQLException {
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
