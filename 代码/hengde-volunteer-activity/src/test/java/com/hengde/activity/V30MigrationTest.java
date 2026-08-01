package com.hengde.activity;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V29 → V30 <b>带存量数据</b>的增量迁移验证。
 *
 * <p>为什么单开容器：其余 {@code @SpringBootTest} 起容器时 Flyway 一次性跑到最新版本，
 * 建表时 {@code slot_id} 就已存在，<b>回填与前置断言一行都没被执行过</b>。
 * 要验证存量数据怎么过这一关，只能先把库停在 V29、灌进旧格式的数据，再单独跑 V30。
 * 两个用例各用一个干净容器——中止用例会把 Flyway 的 schema history 留在失败态，不能与正常路径共用。</p>
 *
 * @author hengde
 */
class V30MigrationTest {

    private MySQLContainer<?> mysql;

    @BeforeEach
    void startContainer() {
        mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"));
        mysql.start();
    }

    @AfterEach
    void stopContainer() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    /**
     * 正常路径：回填的两条分支各自走对，且推断留下审计。
     *
     * <ol>
     *   <li>有已通过报名 → 按<b>报名场次</b>归属（不是想当然取最早的一场）；</li>
     *   <li>无报名但活动有场次 → 兜底到最早场次，并标注这是较弱的推断。</li>
     * </ol>
     */
    @Test
    void v29ToV30_backfillsFromEnrollment_andFallsBackToEarliestSlot() throws Exception {
        flywayTo("29");

        try (Connection c = conn(); Statement s = c.createStatement()) {
            assertNull(columnType(c, "activity_attendance", "slot_id"),
                    "前置条件：V29 的 activity_attendance 不应有 slot_id，否则本用例测不到回填");

            // 活动 A：两个场次，志愿者 1001 报的是【下午场 12】——回填必须跟着报名走
            s.executeUpdate(insertActivity(1, "A_两场有报名", "2026-06-01 09:00:00", "2026-06-01 18:00:00"));
            s.executeUpdate(insertSlot(11, 1, "上午", "2026-06-01 09:00:00", "2026-06-01 12:00:00"));
            s.executeUpdate(insertSlot(12, 1, "下午", "2026-06-01 14:00:00", "2026-06-01 18:00:00"));
            s.executeUpdate("INSERT INTO activity_enrollment (id, activity_id, slot_id, volunteer_id, status, is_deleted) "
                    + "VALUES (101, 1, 12, 1001, 1, 0)");
            s.executeUpdate(insertAttendance(201, 1, 1001, "2026-06-01 14:05:00", 235));
            s.executeUpdate("INSERT INTO activity_violation (id, activity_id, volunteer_id, violation_type, is_deleted) "
                    + "VALUES (301, 1, 1001, 1, 0)");

            // 活动 B：有场次但志愿者 1002 无报名记录（后台补录/脏数据）→ 兜底挂最早场次
            s.executeUpdate(insertActivity(2, "B_有场次无报名", "2026-06-02 09:00:00", "2026-06-02 12:00:00"));
            s.executeUpdate(insertSlot(21, 2, "唯一场", "2026-06-02 09:00:00", "2026-06-02 12:00:00"));
            s.executeUpdate(insertAttendance(202, 2, 1002, "2026-06-02 09:10:00", 170));
        }

        flywayTo("30");

        try (Connection c = conn()) {
            assertNotNull(columnType(c, "activity_attendance", "slot_id"), "V30 后应有 slot_id 列");

            // 分支 1：按报名归属——取的是他真正报的下午场 12，不是最早的 11
            assertEquals(12L, longOf(c, "SELECT slot_id FROM activity_attendance WHERE id = 201"),
                    "有已通过报名时必须按报名场次归属");
            assertEquals("enrollment", strOf(c,
                    "SELECT resolution FROM v30_attendance_slot_backfill_log "
                            + "WHERE table_name='activity_attendance' AND row_id=201"));

            // 分支 2：无报名 → 兜底到活动最早场次，并如实标注为较弱推断
            assertEquals(21L, longOf(c, "SELECT slot_id FROM activity_attendance WHERE id = 202"));
            assertEquals("earliest_slot", strOf(c,
                    "SELECT resolution FROM v30_attendance_slot_backfill_log "
                            + "WHERE table_name='activity_attendance' AND row_id=202"));

            // 违规跟着同人同活动的考勤走
            assertEquals(12L, longOf(c, "SELECT slot_id FROM activity_violation WHERE id = 301"),
                    "违规应落到该志愿者实际参加的那一场");

            // 约束确实换成了场次粒度
            assertEquals(0L, indexCount(c, "activity_attendance", "uk_activity_volunteer"),
                    "旧唯一键应已删除");
            assertTrue(indexCount(c, "activity_attendance", "uk_activity_volunteer_slot") > 0,
                    "新唯一键应存在");
            assertEquals("NO", strOf(c,
                            "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE table_schema = DATABASE() "
                                    + "AND table_name='activity_attendance' AND column_name='slot_id'"),
                    "slot_id 应为 NOT NULL");

            // 成功路径下前置检查用的临时表应已清理
            assertNull(columnType(c, "v30_precheck_gate", "orphan_rows"), "闸门表应在成功后被撤掉");
        }
    }

    /**
     * 「活动一个场次都没有却已有考勤」必须让迁移<b>直接失败</b>，不得静默删除。
     *
     * <p>这种数据不该被任何有效流程产生，出现即代表数据不变量已破坏。考勤行还挂着积分账本、
     * 考勤变更审核、补录单等关联事实——删掉主体会把一处已知损坏换成多处静默悬空引用。</p>
     */
    @Test
    void v29ToV30_activityWithoutAnySlot_abortsMigrationAndListsOffendingRows() throws Exception {
        flywayTo("29");

        try (Connection c = conn(); Statement s = c.createStatement()) {
            // 活动 C：一个场次都没有，却已有考勤
            s.executeUpdate(insertActivity(3, "C_无场次", "2026-06-03 09:00:00", "2026-06-03 12:00:00"));
            s.executeUpdate(insertAttendance(203, 3, 1003, "2026-06-03 09:00:00", 180));
        }

        RuntimeException ex = assertThrows(RuntimeException.class, () -> flywayTo("30"),
                "无场次却有考勤时，V30 必须中止而不是删数据");
        assertTrue(rootMessage(ex).contains("ck_v30_abort_attendance_on_activity_without_slot"),
                "报错应指出中止原因（约束名即原因），实际：" + rootMessage(ex));

        try (Connection c = conn()) {
            // 关键：那行考勤还在，没有被删
            assertEquals(1L, longOf(c, "SELECT COUNT(*) FROM activity_attendance WHERE id = 203"),
                    "中止时不得删除任何考勤行");
            // 且问题行被列了出来，供人工处置
            assertEquals(1L, longOf(c, "SELECT COUNT(*) FROM v30_precheck_orphan"));
            assertEquals(203L, longOf(c, "SELECT row_id FROM v30_precheck_orphan "
                    + "WHERE table_name='activity_attendance'"));
            assertEquals(3L, longOf(c, "SELECT activity_id FROM v30_precheck_orphan "
                    + "WHERE table_name='activity_attendance'"));
        }
    }

    // ---------- 工具 ----------

    private void flywayTo(String version) {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            sb.append(cur.getMessage()).append('\n');
        }
        return sb.toString();
    }

    private Connection conn() throws Exception {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private static String insertActivity(long id, String title, String start, String end) {
        return "INSERT INTO activity (id, title, start_time, end_time, points_base, need_audit, enroll_scope, "
                + "require_min_join_count, min_projects, notice_countdown_sec, status, is_deleted) VALUES ("
                + id + ", '" + title + "', '" + start + "', '" + end + "', 100, 0, 0, 0, 0, 0, 1, 0)";
    }

    private static String insertSlot(long id, long activityId, String name, String start, String end) {
        return "INSERT INTO activity_slot (id, activity_id, project_name, start_time, end_time, need_count, is_deleted) "
                + "VALUES (" + id + ", " + activityId + ", '" + name + "', '" + start + "', '" + end + "', 10, 0)";
    }

    private static String insertAttendance(long id, long activityId, long volunteerId, String checkIn, int minutes) {
        return "INSERT INTO activity_attendance (id, activity_id, volunteer_id, check_in_time, service_minutes, "
                + "secretary_status, points_status, points_factor, is_deleted) VALUES ("
                + id + ", " + activityId + ", " + volunteerId + ", '" + checkIn + "', " + minutes + ", 1, 1, 0, 0)";
    }

    private static Long longOf(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : null;
        }
    }

    private static String strOf(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static Long indexCount(Connection c, String table, String index) throws Exception {
        return longOf(c, "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE table_schema = DATABASE() "
                + "AND table_name='" + table + "' AND index_name='" + index + "'");
    }

    /** 列的数据类型；列（或表）不存在返回 null。 */
    private static String columnType(Connection c, String table, String column) throws Exception {
        return strOf(c, "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE table_schema = DATABASE() "
                + "AND table_name = '" + table + "' AND column_name = '" + column + "'");
    }
}
