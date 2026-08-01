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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V25 → V26 的<b>真实升级测试</b>：验证「快照完成标记表」能在存量库上补建出来。
 *
 * <p><b>为什么必须专门测这一步</b>：honor 模块的用例走的是常规 {@code @SpringBootTest}，
 * Flyway 一次性把空库迁到最新（V1→V26），那条路径永远成功，<b>证明不了增量升级可用</b>。
 * 而本表是在评审中才补出来的，它面对的恰恰是「库已经停在 V25」的存量场景。</p>
 *
 * <p><b>覆盖两个真实存在的历史状态</b>（与 V26 抬头的场景编号一一对应）：</p>
 * <ul>
 *   <li>场景 ②——库跑过<b>最终版 V25</b>（不含本表）：V26 应把表补建出来；</li>
 *   <li>场景 ③——库跑过<b>中间版 V25</b>（开发期一度把本表写在 V25 里）：该表已存在，
 *       V26 必须<b>照样能过</b>而不是撞「表已存在」卡死升级。这正是 V26 破例使用
 *       {@code CREATE TABLE IF NOT EXISTS} 的理由，此处把它钉死。</li>
 * </ul>
 *
 * <p>场景 ③ 只模拟「表已存在」这一后果，不模拟 checksum 不匹配——后者是 Flyway 自身的行为，
 * 由运维执行 {@code repair} 处理（步骤写在 V26 抬头），不属本测试要验证的范围。</p>
 *
 * <p>本类<b>刻意不用 {@code @SpringBootTest}</b>、且<b>放在 common 而非 honor</b>，
 * 理由与同包的 {@link V24BackfillMigrationTest} 完全一致（前者要自己掌控迁移目标版本，
 * 后者是为了永远直接读本模块当前的迁移脚本，而非本地仓库里的旧 SNAPSHOT）。</p>
 *
 * <p><b>需本机有 Docker。</b></p>
 *
 * @author hengde
 */
class V26HonorRankingBatchMigrationTest {

    private static final String MIGRATIONS = "classpath:db/migration";

    private static final String BATCH_TABLE = "honor_ranking_snapshot_batch";

    /** 与 V26 里的 DDL 保持一致——场景 ③ 要模拟的正是「中间版 V25 已经建好了同一张表」 */
    private static final String INTERMEDIATE_V25_DDL = """
            CREATE TABLE honor_ranking_snapshot_batch (
                id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
                period_type TINYINT     NOT NULL COMMENT '周期 1月/2年',
                period_key  VARCHAR(16) NOT NULL COMMENT '周期标识：月 2026-07 / 年 2026',
                rank_type   TINYINT     NOT NULL COMMENT '榜单 1活动次数/2活动时长/3积分',
                row_count   INT         NOT NULL DEFAULT 0 COMMENT '本次冻结写入的行数（0 表示空榜）',
                create_time DATETIME    DEFAULT NULL,
                update_time DATETIME    DEFAULT NULL,
                is_deleted  TINYINT     NOT NULL DEFAULT 0,
                PRIMARY KEY (id),
                UNIQUE KEY uk_batch (period_type, period_key, rank_type)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '荣誉排行榜快照完成标记'
            """;

    /** 手动管理容器生命周期，理由同 {@link V24BackfillMigrationTest} */
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

    /** 每个用例都要从空库重来，否则无法再迁回 V25 */
    @BeforeEach
    void resetDatabase() {
        cleanDatabase();
    }

    /** 场景 ②：库停在最终版 V25（无本表），V26 应补建。 */
    @Test
    void upgradeFromV25_createsBatchTable() throws Exception {
        migrateTo("25");
        assertFalse(tableExists(BATCH_TABLE),
                "V25 不该建这张表——它若在 V25 里，本测试要防的整个问题就不存在了");

        migrateTo("26");

        assertTrue(tableExists(BATCH_TABLE), "V26 应在存量库上把完成标记表补建出来");
        assertTrue(hasUniqueKey(BATCH_TABLE, "uk_batch"),
                "唯一键缺失会让强制重跑撞不到覆盖写，冻结状态可能出现重复行");
        assertEquals(26, currentVersion(), "迁移应停在 V26");
    }

    /**
     * 场景 ③：库跑过中间版 V25，表已存在。V26 必须照样能过。
     *
     * <p>若 V26 写成裸 {@code CREATE TABLE}，这里会抛
     * {@code Table 'honor_ranking_snapshot_batch' already exists} 而整条升级卡死。</p>
     */
    @Test
    void upgradeFromIntermediateV25_toleratesExistingBatchTable() throws Exception {
        migrateTo("25");
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute(INTERMEDIATE_V25_DDL);
        }
        assertTrue(tableExists(BATCH_TABLE), "前置：模拟中间版 V25 已建好该表");

        migrateTo("26");

        assertTrue(tableExists(BATCH_TABLE), "表应原样保留");
        assertEquals(26, currentVersion(), "V26 应记为已执行，而不是卡在表已存在");
    }

    // ---------- helpers ----------

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

    private boolean tableExists(String table) throws SQLException {
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                             + " AND table_name = '" + table + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private boolean hasUniqueKey(String table, String keyName) throws SQLException {
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()"
                             + " AND table_name = '" + table + "' AND index_name = '" + keyName + "'"
                             + " AND non_unique = 0")) {
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
