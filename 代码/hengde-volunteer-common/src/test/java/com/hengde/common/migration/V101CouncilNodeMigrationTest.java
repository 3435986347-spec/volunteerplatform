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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * V100 → V101：组织架构补种「理事会」节点（协会 2026-08-11：理事会是组织架构里的一个节点）。
 *
 * <p><b>为什么要专门测</b>：其余用例都是空库一路迁到最新，只碰得到「V5 种子原样」这一种库；
 * 而线上库在 V69 之后可能已经被超管在后台改过架构——先建了理事会（挂在根下或别处）、或建了又删了。
 * 只有先迁到 V100、把库摆成这几种样子、再迁 V101，才看得出它会不会多种一个、会不会漏种。</p>
 *
 * <p><b>需本机有 Docker。</b></p>
 *
 * @author hengde
 */
class V101CouncilNodeMigrationTest {

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
    void freshDatabaseAtV100() {
        flyway(null).clean();
        flyway("100").migrate();
    }

    @Test
    void seedsCouncilUnderTheRootBeforeTheOtherDepartments() throws SQLException {
        long root = root();

        flyway(null).migrate();

        List<String> children = childrenOf(root);
        assertEquals(List.of("理事会", "组织部", "秘书部", "宣传部"), children,
                "理事会挂在协会根节点下、排在三个部门前面（原型 P19 的顺序）");
    }

    @Test
    void doesNotAddASecondCouncilWhenAnAdminAlreadyCreatedOne() throws SQLException {
        long root = root();
        long existing = node(root, "理事会", "后台先建的", 99, false);

        flyway(null).migrate();

        assertEquals(1, countLiveCouncils(root), "后台已经建过的不再种第二个");
        assertEquals(existing, liveCouncilId(root), "留下的是后台建的那一个");
    }

    @Test
    void aDeletedCouncilDoesNotCount() throws SQLException {
        long root = root();
        long deleted = node(root, "理事会", "删掉的", 5, true);

        flyway(null).migrate();

        assertEquals(1, countLiveCouncils(root), "已删的不算，照样补种一个");
        assertNotEquals(deleted, liveCouncilId(root), "补的是新的一行，不是把删掉的那行复活");
    }

    @Test
    void anExistingCouncilElsewhereInTheTreeIsLeftAlone() throws SQLException {
        long root = root();
        long org = childId(root, "组织部");
        long underDept = node(org, " 理事会 ", "挂在组织部下面的", 1, false);   // 名称首尾带空格：后台手填常有

        flyway(null).migrate();

        assertEquals(0, countLiveCouncils(root), "超管已经把理事会放在别处，迁移不在根下再添一个同名的");
        assertEquals(1, countByName("理事会"), "整棵树里仍只有后台建的那一个");
        assertEquals(underDept, idByName("理事会"), "留下的是挂在组织部下面的那一个");
    }

    // ------------------------------------------------------------------ helpers

    private long root() throws SQLException {
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM organization_structure_node "
                     + "WHERE parent_id IS NULL AND is_deleted = 0 ORDER BY id LIMIT 1")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long node(long parentId, String name, String title, int sort, boolean deleted) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO organization_structure_node (parent_id, name, title, sort, create_time, is_deleted) "
                        + "VALUES (?, ?, ?, ?, NOW(), ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, parentId);
            ps.setString(2, name);
            ps.setString(3, title);
            ps.setInt(4, sort);
            ps.setInt(5, deleted ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long childId(long parentId, String name) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM organization_structure_node WHERE parent_id = ? AND name = ? AND is_deleted = 0")) {
            ps.setLong(1, parentId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 整棵树里名称（去首尾空格后）等于 name 的未删节点数。 */
    private int countByName(String name) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM organization_structure_node WHERE TRIM(name) = ? AND is_deleted = 0")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private long idByName(String name) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM organization_structure_node WHERE TRIM(name) = ? AND is_deleted = 0")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private List<String> childrenOf(long parentId) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM organization_structure_node WHERE parent_id = ? AND is_deleted = 0 ORDER BY sort, id")) {
            ps.setLong(1, parentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private int countLiveCouncils(long parentId) throws SQLException {
        return (int) childrenOf(parentId).stream().filter("理事会"::equals).count();
    }

    private long liveCouncilId(long parentId) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM organization_structure_node WHERE parent_id = ? AND name = '理事会' AND is_deleted = 0")) {
            ps.setLong(1, parentId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
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
