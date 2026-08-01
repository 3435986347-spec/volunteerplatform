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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V24 历史积分回填的<b>真实升级测试</b>：在存量库上跑迁移，验证回填结果。
 *
 * <p><b>为什么必须单独建这个类</b>：常规 {@code @SpringBootTest} 启动时 Flyway 已把库迁到最新，
 * 那时表里一行业务数据都没有，回填语句扫过 0 行——迁移「成功」只证明 SQL 语法有效，
 * 而 WHERE 写反、source_id/时间映射错列、甚至整条 INSERT 被删掉，测试都照样绿。
 * 唯一能真正覆盖它的办法就是模拟真实升级：<b>先迁到 V23 → 灌入历史数据 → 再迁到 V24 → 断言 point_record</b>。</p>
 *
 * <p>本类<b>刻意不用 {@code @SpringBootTest}</b>（与项目「测试统一加载 Spring 上下文」的约定不同）：
 * 它要自己掌控 Flyway 的迁移目标版本与容器生命周期，而 Spring 上下文一启动就会把库迁到最新，
 * 正好毁掉待测场景。同类先例见同模块的 {@code QrCodeUtilTest}/{@code FileValidatorTest}。</p>
 *
 * <p><b>为什么放在 common 而不是 activity</b>：迁移脚本是 common 的资源。若把本类放在 activity，
 * 单独跑 activity 模块时 classpath 上的 common 可能是<b>本地仓库里已安装的旧 SNAPSHOT</b>，
 * 于是「V24 被改坏」仍然测试全绿——测试保护的对象和被测的对象不是同一份文件。
 * 放在 common 则永远直接读本模块 {@code src/main/resources} 下的当前脚本。</p>
 *
 * <p><b>需本机有 Docker。</b></p>
 *
 * @author hengde
 */
class V24BackfillMigrationTest {

    private static final String MIGRATIONS = "classpath:db/migration";

    /** 两个可区分的固定时间，用于验证回填的时间映射（取 update_time，为空回落 create_time） */
    private static final String CREATED_AT = "2026-01-02 08:15:00";
    private static final String UPDATED_AT = "2026-03-04 19:45:00";

    /**
     * 手动管理容器生命周期，而非用 {@code @Testcontainers}/{@code @Container}——
     * 那两个注解在 {@code org.testcontainers:junit-jupiter} 里，本项目未引该模块；
     * 为一个用例新增依赖不值当，手动 start/stop 效果相同。
     */
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

    /** 两个用例共用同一个容器，各自都要从空库重来，否则会看到对方的数据、也无法再迁回 V23 */
    @BeforeEach
    void resetDatabase() {
        cleanDatabase();
    }

    @Test
    void backfillsGrantedPointsOnly_withActivityTitle() throws Exception {
        migrateTo("23");

        try (Connection c = connect()) {
            insertActivity(c, 9001L, "敬老院探访");
            insertActivity(c, 9002L, "海滩清洁");

            // ① 已发放 30 分 → 应回填
            insertAttendance(c, 5001L, 701L, 9001L, 30, 1);
            // ② 已发放 12 分，另一活动 → 应回填
            insertAttendance(c, 5002L, 701L, 9002L, 12, 1);
            // ③ 未发放（points_status=0）→ 不应回填，否则志愿者凭空多分
            insertAttendance(c, 5003L, 702L, 9001L, 50, 0);
            // ④ 已发放但 award=0（历史活动补录，只记时长不发分）→ 不应回填空账
            //    换用 9002：uk_activity_volunteer 限定「一个活动一人一条考勤」，不能与 ③ 同活动
            insertAttendance(c, 5004L, 702L, 9002L, 0, 1);
            // ⑤ 已逻辑删除的考勤 → 不应回填
            insertAttendance(c, 5005L, 703L, 9001L, 99, 1);
            exec(c, "UPDATE activity_attendance SET is_deleted = 1 WHERE id = 5005");
            // ⑥ 活动行不存在（脏数据）→ 仍应回填，remark 回落占位文案
            insertAttendance(c, 5006L, 704L, 8888888L, 7, 1);
            // ⑦ update_time 为空 → 时间应回落 create_time（COALESCE 分支）
            insertActivity(c, 9003L, "无更新时间的活动");
            insertAttendance(c, 5007L, 705L, 9003L, 5, 1, CREATED_AT, null);
        }

        migrateTo("24");

        try (Connection c = connect()) {
            // 合格的只有 5001/5002/5006/5007；5003 未发放、5004 金额为 0、5005 已逻辑删除
            assertEquals(4, count(c, "SELECT COUNT(*) FROM point_record"),
                    "只应回填「已发放且金额非零且未删除」的 4 条");

            // 金额与来源映射
            assertEquals(42, count(c, "SELECT COALESCE(SUM(change_amount),0) FROM point_record "
                    + "WHERE volunteer_id = 701"), "701 应为 30+12");
            assertEquals(1, count(c, "SELECT COUNT(*) FROM point_record "
                    + "WHERE volunteer_id = 701 AND source_type = 1 AND source_id = 5001 AND change_amount = 30"),
                    "source_id 应指向考勤 id、来源类型应为活动积分");

            // 未发放 / 零额 / 已删除都不得入账
            assertEquals(0, count(c, "SELECT COUNT(*) FROM point_record WHERE volunteer_id = 702"),
                    "未发放与零额都不应回填");
            assertEquals(0, count(c, "SELECT COUNT(*) FROM point_record WHERE volunteer_id = 703"),
                    "已逻辑删除的考勤不应回填");

            // remark 带活动名——明细搜索框搜的就是它，存量数据必须可按活动名检索
            assertEquals("参加活动「敬老院探访」（历史回填）",
                    queryString(c, "SELECT remark FROM point_record WHERE source_id = 5001"));
            assertEquals("参加活动「海滩清洁」（历史回填）",
                    queryString(c, "SELECT remark FROM point_record WHERE source_id = 5002"));
            assertEquals("参加活动「未知活动」（历史回填）",
                    queryString(c, "SELECT remark FROM point_record WHERE source_id = 5006"),
                    "活动缺失应回落占位文案，而不是整行丢失或迁移失败");

            // 操作方 / 幂等键
            assertEquals(4, count(c, "SELECT COUNT(*) FROM point_record WHERE operator_type = 0"),
                    "回填是系统行为");
            assertNull(queryString(c, "SELECT request_id FROM point_record WHERE source_id = 5001"),
                    "回填不占用幂等键");

            // 时间映射：优先取考勤的 update_time；明细正是按 create_time 排序的，映射错了顺序就乱
            assertEquals(UPDATED_AT, queryString(c,
                            "SELECT DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') FROM point_record WHERE source_id = 5001"),
                    "应取考勤的 update_time，而不是 create_time 或 NOW()");
            assertEquals(UPDATED_AT, queryString(c,
                    "SELECT DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') FROM point_record WHERE source_id = 5001"));
            // 回落分支：create_time 与 update_time 两个表达式各写一遍，必须<b>都</b>断言，
            // 否则单独写坏其中一个仍会绿
            assertEquals(CREATED_AT, queryString(c,
                            "SELECT DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') FROM point_record WHERE source_id = 5007"),
                    "考勤 update_time 为空时 create_time 应回落 create_time");
            assertEquals(CREATED_AT, queryString(c,
                            "SELECT DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') FROM point_record WHERE source_id = 5007"),
                    "考勤 update_time 为空时 update_time 也应回落 create_time");
        }
    }

    /** 回填后账本余额应与旧口径 SUM(points_award) 一致，否则升级当天志愿者的积分会跳变 */
    @Test
    void backfilledBalanceMatchesLegacyPointsAward() throws Exception {
        migrateTo("23");
        try (Connection c = connect()) {
            insertActivity(c, 9101L, "迁移对账活动甲");
            insertActivity(c, 9102L, "迁移对账活动乙");
            insertAttendance(c, 6001L, 801L, 9101L, 15, 1);
            insertAttendance(c, 6002L, 801L, 9102L, 25, 1);   // 同一人的第二条须换活动（uk_activity_volunteer）
        }
        migrateTo("24");

        try (Connection c = connect()) {
            long legacy = count(c, "SELECT COALESCE(SUM(points_award),0) FROM activity_attendance "
                    + "WHERE volunteer_id = 801 AND points_status = 1 AND is_deleted = 0");
            long ledger = count(c, "SELECT COALESCE(SUM(change_amount),0) FROM point_record WHERE volunteer_id = 801");
            assertEquals(legacy, ledger, "账本余额必须等于升级前的旧口径积分");
            assertEquals(40, ledger);
        }
    }

    // ---------- helpers ----------

    /**
     * 迁到指定版本。{@code cleanDisabled(false)} 允许本类的第二个用例从干净库重来——
     * 两个用例共用同一个容器，不清库会互相看到对方的数据。
     */
    private void migrateTo(String version) {
        Flyway flyway = Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations(MIGRATIONS)
                .target(version)
                .cleanDisabled(false)
                .load();
        flyway.migrate();
    }

    private void cleanDatabase() {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations(MIGRATIONS)
                .cleanDisabled(false)
                .load()
                .clean();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private void insertActivity(Connection c, long id, String title) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO activity (id, title, start_time, end_time, status, create_time, is_deleted) "
                        + "VALUES (?, ?, '2026-01-01 09:00:00', '2026-01-01 17:00:00', 2, NOW(), 0)")) {
            ps.setLong(1, id);
            ps.setString(2, title);
            ps.executeUpdate();
        }
    }

    private void insertAttendance(Connection c, long id, long volunteerId, long activityId,
                                  int award, int pointsStatus) throws SQLException {
        insertAttendance(c, id, volunteerId, activityId, award, pointsStatus, CREATED_AT, UPDATED_AT);
    }

    /**
     * 造考勤行，{@code createTime}/{@code updateTime} 显式给<b>不同</b>的固定时间。
     *
     * <p>早前两列都写 {@code NOW()}，回填哪怕映射错列、甚至直接写 {@code NOW()}，
     * 「非空」断言也照样通过——等于没验时间映射。给两个可区分的值才能锁死
     * 「取 update_time，为空回落 create_time」这条 COALESCE 逻辑。</p>
     */
    private void insertAttendance(Connection c, long id, long volunteerId, long activityId,
                                  int award, int pointsStatus,
                                  String createTime, String updateTime) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO activity_attendance (id, activity_id, volunteer_id, points_award, points_status, "
                        + "create_time, update_time, is_deleted) VALUES (?, ?, ?, ?, ?, ?, ?, 0)")) {
            ps.setLong(1, id);
            ps.setLong(2, activityId);
            ps.setLong(3, volunteerId);
            ps.setInt(4, award);
            ps.setInt(5, pointsStatus);
            ps.setString(6, createTime);
            ps.setString(7, updateTime);
            ps.executeUpdate();
        }
    }

    private void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private long count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String queryString(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
