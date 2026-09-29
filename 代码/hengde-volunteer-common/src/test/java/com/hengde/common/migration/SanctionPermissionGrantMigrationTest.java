package com.hengde.common.migration;

import com.hengde.common.testsupport.TestcontainersConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * V85 → V86：按协会 2026-09-16 的部门分工，给<b>已有</b>后台账号补授三档限制权限。
 *
 * <p><b>为什么必须专门测</b>：V86 只作用于迁移那一刻已经存在的账号，而其余用例都是「空库一路迁到最新」——
 * 那条路上一个后台账号都没有，这条 INSERT 一行也不会插，写错部门名、漏掉理事会、把超管或已删账号也授上，
 * 全都照样绿。只有先迁到 V85、造出各部门的账号、再迁到 V86，才碰得到它。</p>
 *
 * <p><b>要钉住的</b>：组织部 / 宣传部 / 秘书部各拿到自己那一档；理事会三档都有；别的部门、超管、已删账号一个不给；
 * 已有授权不撤；<b>曾被撤销过的授权（软删行）不在迁移里悄悄恢复</b>。</p>
 *
 * <p><b>需本机有 Docker。</b></p>
 *
 * @author hengde
 */
class SanctionPermissionGrantMigrationTest {

    private static final String ACTIVITY = "honor:sanction-activity";
    private static final String COMMUNITY = "honor:sanction-community";
    private static final String ALL = "honor:sanction-all";

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

    @Test
    void existingAccountsGetTheirDepartmentsSanctionPermissions() throws SQLException {
        flyway("85").migrate();

        long org = admin("org", "组织部", false, false);
        long pub = admin("pub", "宣传部", false, false);
        long sec = admin("sec", "秘书部", false, false);
        long council = admin("council", "理事会", false, false);
        long other = admin("other", "财务部", false, false);
        long noDept = admin("nodept", null, false, false);
        long superAdmin = admin("super", "理事会", true, false);
        long deleted = admin("deleted", "组织部", false, true);
        // 宣传部里一个早就持有「拒绝使用本程序」的账号：原有授权不撤，另补上社区那一档
        long pubWithAll = admin("puball", "宣传部", false, false);
        grant(pubWithAll, ALL, false);
        // 秘书部里一个「拒绝使用本程序」曾被超管撤销过的账号（软删行）：迁移不能替超管把它恢复回来
        long secRevoked = admin("secrevoked", "秘书部", false, false);
        grant(secRevoked, ALL, true);

        flyway(null).migrate();

        assertEquals(Set.of(ACTIVITY), granted(org), "组织部：限制参加活动");
        assertEquals(Set.of(COMMUNITY), granted(pub), "宣传部：限制发布社区");
        assertEquals(Set.of(ALL), granted(sec), "秘书部：拒绝使用本程序");
        assertEquals(Set.of(ACTIVITY, COMMUNITY, ALL), granted(council), "理事会拥有全部限制权限");
        assertEquals(Set.of(), granted(other), "别的部门一个不给");
        assertEquals(Set.of(), granted(noDept), "没填部门的一个不给");
        assertEquals(Set.of(), granted(superAdmin), "超管走 * 通配，不必授");
        assertEquals(Set.of(), granted(deleted), "已删账号不授");
        assertEquals(Set.of(ALL, COMMUNITY), granted(pubWithAll), "已有授权不撤，另补本部门那一档");
        assertEquals(Set.of(), granted(secRevoked), "被撤销过的授权不在迁移里悄悄恢复");
    }

    private long admin(String username, String department, boolean superAdmin, boolean deleted) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO admin_user (username, password, department, is_super_admin, status, create_time, is_deleted) "
                        + "VALUES (?, 'x', ?, ?, 0, NOW(), ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, department);
            ps.setInt(3, superAdmin ? 1 : 0);
            ps.setInt(4, deleted ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void grant(long adminId, String code, boolean revoked) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO admin_permission (admin_user_id, permission_id, create_time, is_deleted) "
                        + "SELECT ?, id, NOW(), ? FROM permission WHERE code = ?")) {
            ps.setLong(1, adminId);
            ps.setInt(2, revoked ? 1 : 0);
            ps.setString(3, code);
            assertEquals(1, ps.executeUpdate(), "权限点 " + code + " 应在 V85 之前已存在");
        }
    }

    /** 这个账号当前有效（未软删）的三档限制权限。 */
    private Set<String> granted(long adminId) throws SQLException {
        Set<String> codes = new TreeSet<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT p.code FROM admin_permission ap JOIN permission p ON p.id = ap.permission_id "
                        + "WHERE ap.admin_user_id = ? AND ap.is_deleted = 0 AND p.code IN (?, ?, ?)")) {
            ps.setLong(1, adminId);
            ps.setString(2, ACTIVITY);
            ps.setString(3, COMMUNITY);
            ps.setString(4, ALL);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    codes.add(rs.getString(1));
                }
            }
        }
        return codes;
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
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
