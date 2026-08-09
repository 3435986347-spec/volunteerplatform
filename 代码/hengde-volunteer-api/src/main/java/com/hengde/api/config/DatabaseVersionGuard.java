package com.hengde.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据库版本守卫：MySQL 低于 {@value #MIN_VERSION} 直接拒绝启动，<b>且在 Flyway 迁移之前就拒绝</b>。
 *
 * <p><b>为什么要专门拦这一条</b>：两个迁移把数据库特性当作硬前提。</p>
 * <ul>
 *   <li><b>CHECK 约束（8.0.16 起）</b>——V32 的 {@code ck_rp_sanction_days}（天数为正、且必须有能力域）
 *       与 V33 的 {@code ck_rp_sanction_days_cap}（≤ 3650 天）。
 *       <b>8.0.16 之前 MySQL 会解析 CHECK 却不执行</b>：建表照常成功、迁移照常「通过」，约束形同注释。
 *       「绕过 service 的写路径也进不了脏数据」这个前提整个不成立，而且<b>没有任何征兆</b>——
 *       直到某天一条 99999 天的处置让 {@code now.plusDays(N)} 抛 {@code DateTimeException} 报 500。</li>
 *   <li><b>{@code utf8mb4_0900_bin} 排序规则（8.0.17 起）</b>——V34 靠它让「库里的相等」与
 *       「Java 的相等」重合。8.0.16 上执行 V34 会得到 {@code ERROR 1273 Unknown collation}
 *       （已在 {@code mysql:8.0.16} 容器实测）。</li>
 * </ul>
 * <p>两者取大 ⇒ <b>最低版本 {@value #MIN_VERSION}</b>。</p>
 *
 * <p><b>为什么必须跑在 Flyway 之前</b>（本类第 6 轮评审时从 {@code ApplicationReadyEvent} 前移）：
 * 放在迁移之后，唯一能做的是「事后让实例起不来」——迁移<b>已经执行过了</b>。
 * 版本过低时它未必是干净地整体失败：V33 会成功并隐式提交、V34 才因未知排序规则失败，
 * 于是数据库停在一个「做了一半、升级后 repair 重跑还会撞约束重名」的状态。
 * 守卫的意义是<b>保护数据库</b>，不是给一个迟到的告警。故走 {@link FlywayMigrationStrategy}：
 * 先校验，再 {@code migrate()}。</p>
 *
 * <p><b>{@code ApplicationReadyEvent} 那条只是兜底</b>：Flyway 被关掉（{@code spring.flyway.enabled=false}）
 * 或换成外部迁移工具时，上面的策略压根不会被调用，而版本前提依然成立。已校验过就不再重复。</p>
 *
 * <p><b>所有 profile 一律校验</b>，不像 {@link ProductionConfigGuard} 那样放行 dev——
 * 开发库版本过低同样会让本地测出来的「约束生效」是假象，那种假象比线上事故更难查。</p>
 *
 * <p><b>fail closed</b>：确认是 MySQL 却解析不出版本号时<b>拒绝启动</b>，不放行。
 * 这是硬性前提，「读不出来就当它没问题」正是本类要消灭的那类静默假设。
 * 非 MySQL（未来若换库）仍只记 warn：那时 V33/V34 这些 MySQL 方言迁移本就跑不起来，
 * 本守卫的职责是挡住<b>已知会静默失效</b>的那一档，不是充当通用的环境检查。</p>
 *
 * @author hengde
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class DatabaseVersionGuard {

    /** 最低支持版本：8.0.16 起 CHECK 才执行，8.0.17 起才有 {@value #REQUIRED_COLLATION}，取大者 */
    public static final String MIN_VERSION = "8.0.17";

    /** V34 直接依赖的排序规则——版本号之外还要探它是否真的存在，理由见 {@link #verify} */
    public static final String REQUIRED_COLLATION = "utf8mb4_0900_bin";

    private static final int[] MIN = {8, 0, 17};

    /** 迁移前已经校验过就不再重复；兜底那条据此判断要不要跑 */
    private final AtomicBoolean verified = new AtomicBoolean(false);

    private DataSource dataSource;

    @Autowired
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 替换 Spring Boot 默认的迁移策略（默认就是一句 {@code flyway.migrate()}），
     * 在其之前插入版本校验。校验抛异常即启动失败，<b>一条迁移都不会执行</b>。
     */
    @Bean
    public FlywayMigrationStrategy databaseVersionCheckingMigrationStrategy() {
        return flyway -> {
            try {
                verify(flyway.getConfiguration().getDataSource());
            } catch (SQLException e) {
                // 连不上就更不该往下迁移；包成运行期异常直接中断启动
                throw new IllegalStateException("迁移前无法读取数据库版本信息", e);
            }
            flyway.migrate();
        };
    }

    /** 兜底：Flyway 被禁用时上面的策略不会被调用，版本前提却依然成立。 */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyIfMigrationDidNotRun() throws SQLException {
        if (!verified.get()) {
            log.warn("未经由 Flyway 迁移策略校验数据库版本（Flyway 可能已禁用），在启动完成后补校验一次");
            verify(dataSource);
        }
    }

    /**
     * 两道检查：版本号 ≥ {@link #MIN_VERSION}，<b>且</b>目标能力真的存在。
     *
     * <p><b>为什么不能只比版本号</b>：版本号是<b>代号</b>，迁移要的是<b>能力</b>。
     * {@code getDatabaseProductName()} 返回 "MySQL" 只说明连的是 MySQL <b>协议</b>——
     * MariaDB / Percona 及各家云托管分支经 {@code mysql-connector-j} 连接时产品名同样是 "MySQL"，
     * 而版本号走的是它们自己的编号（MariaDB 10.x / 11.x 一路大于 8.0.17）。
     * 于是一个「自称 MySQL、版本号够格、却没有 {@value #REQUIRED_COLLATION}」的服务端
     * 会大摇大摆通过守卫，然后死在 V34——正是本类唯一要防的那个状态。
     * （MariaDB 是否发 {@code 5.5.5-} 兼容前缀会让 {@link #parse} 拿到 {@code {5,5,5}} 而侥幸拒掉，
     * 但那是运气，不是判据。）</p>
     *
     * <p>成本是一条查询，而且连接本来就开着。{@code information_schema} 是普通视图，
     * 不需要额外授权——不像 {@code performance_schema.data_locks}（隔离用例里得另开 root 连接）。</p>
     *
     * <p><b>CHECK 那一档没有同样直接的探针</b>（「约束是否真的执行」只能靠实际插一行脏数据试，
     * 那是启动期不该做的事），但它的门槛是 8.0.16、比排序规则更低，由版本号兜住就够。</p>
     *
     * @throws IllegalStateException 版本过低、确认是 MySQL 却解析不出版本号、或缺少必需的排序规则
     */
    public void verify(DataSource target) throws SQLException {
        try (Connection conn = target.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            String raw = conn.getMetaData().getDatabaseProductVersion();
            verified.set(true);
            if (product == null || !product.toLowerCase().contains("mysql")) {
                log.warn("数据库不是 MySQL（{}），跳过版本校验", product);
                return;
            }
            int[] actual = parse(raw);
            if (actual == null) {
                // fail closed：这是硬性前提，读不出来不等于满足
                throw new IllegalStateException("无法解析 MySQL 版本串「" + raw + "」，"
                        + "而本项目对数据库版本有硬性要求（≥ " + MIN_VERSION + "）。"
                        + "低于该版本时 CHECK 约束不执行、" + REQUIRED_COLLATION + " 排序规则不存在，"
                        + "迁移会做到一半且无法重跑。请确认数据库版本后再启动。");
            }
            if (compare(actual, MIN) < 0) {
                throw new IllegalStateException("MySQL 版本过低：当前 " + raw + "，本项目要求 ≥ " + MIN_VERSION
                        + "。8.0.16 之前 CHECK 约束会被解析但【不执行】（V32/V33 的完整性约束形同注释）；"
                        + "8.0.17 之前没有 " + REQUIRED_COLLATION + " 排序规则（V34 会直接失败）。"
                        + "请升级数据库后再启动。");
            }
            requireCollation(conn, raw);
            log.info("数据库版本校验通过：{} {}（{} 可用）", product, raw, REQUIRED_COLLATION);
        }
    }

    /** V34 直接依赖的排序规则；它在不在，比版本号叫什么更有说服力。 */
    private static void requireCollation(Connection conn, String rawVersion) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.COLLATIONS WHERE COLLATION_NAME = ?")) {
            ps.setString(1, REQUIRED_COLLATION);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return;
                }
            }
        }
        throw new IllegalStateException("数据库自称 MySQL " + rawVersion + "，版本号够格，"
                + "却没有 " + REQUIRED_COLLATION + " 排序规则。V34 会以 ERROR 1273 (Unknown collation) 失败，"
                + "留下一个做了一半、且 repair 重跑还会撞约束重名的数据库。"
                + "最可能的原因是这并不是 MySQL——MariaDB / Percona 等分支经 mysql-connector-j 连接时，"
                + "产品名同样报 MySQL，版本号却是它们自己的编号。请改用 MySQL ≥ " + MIN_VERSION + "。");
    }

    /** 从 {@code 8.0.46-log} 这类版本串里取前三段数字；取不到返回 null。 */
    private static int[] parse(String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.trim().split("[.\\-+ ]");
        int[] out = new int[3];
        int n = 0;
        for (String p : parts) {
            if (n == 3) {
                break;
            }
            if (!p.matches("\\d+")) {
                // 主版本还没取到就遇上非数字（如某些发行版前缀），认定解析失败
                if (n == 0) {
                    return null;
                }
                break;
            }
            out[n++] = Integer.parseInt(p);
        }
        return n == 0 ? null : out;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }
}
