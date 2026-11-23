package com.hengde.api.config;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DatabaseVersionGuard} 的判定本身。<b>不需要 Docker</b>——用动态代理伪造
 * {@code DataSource → Connection → DatabaseMetaData} 这条链，直接喂各种版本串。
 *
 * <p><b>为什么必须单独测</b>：唯一跑到这个守卫的集成用例是 api 模块的启动用例，
 * 而它跑在 {@code mysql:8.0.46} 上——只证明「合格版本不会被误伤」，
 * 反方向（版本过低要拒、版本读不出来要拒）在整套测试里<b>没有任何覆盖</b>。
 * 把边界写错（{@code <} 写成 {@code <=}、fail closed 写成放行）不会有任何征兆，
 * 直到某天有人把库装成 8.0.16，V34 迁移失败并留下一个做了一半的数据库。</p>
 *
 * <p>拉一个真的 8.0.16 容器当然更实在，但那要多下一个镜像、多起一次容器，
 * 而这里要验的全部逻辑就是「版本串 + 能力探针 → 拒 / 放」这套判定。真实低版本上的行为
 * 已由 V33 迁移抬头记录的手工实测（{@code ERROR 1273} / {@code ERROR 3822}）背书。</p>
 *
 * <p>伪造的这条链<b>不只是 metadata</b>：还包括
 * {@code information_schema.COLLATIONS} 那次探针查询的结果——守卫比的是<b>能力</b>，
 * 用例就得能分别摆布「版本号」与「能力」两个维度，才测得出
 * {@link #mysqlProtocolWithoutTheRequiredCollationIsRejected} 那一格。</p>
 *
 * @author hengde
 */
class DatabaseVersionGuardTest {

    private final DatabaseVersionGuard guard = new DatabaseVersionGuard();

    /** 8.0.17 是门槛本身：必须放行，否则约束写成了 {@code >}。 */
    @Test
    void exactFloorIsAccepted() {
        assertDoesNotThrow(() -> guard.verify(fakeSource("MySQL", "8.0.17", true)));
        assertDoesNotThrow(() -> guard.verify(fakeSource("MySQL", "8.0.46-log", true)));
        assertDoesNotThrow(() -> guard.verify(fakeSource("MySQL", "8.4.0", true)));
    }

    /**
     * <b>版本号够格但能力不在，必须拒</b>——守卫比的是能力，不是代号。
     *
     * <p>最现实的形态是 MariaDB / Percona 经 {@code mysql-connector-j} 连接：
     * {@code getDatabaseProductName()} 同样返回 "MySQL"，而版本号走它们自己的编号，
     * 10.x / 11.x 一路大于 8.0.17。只比版本号的话它们会通过守卫，然后死在 V34，
     * 留下一个做了一半、repair 重跑还会撞约束重名的数据库——正是本守卫唯一要防的那个状态。</p>
     *
     * <p>删掉 {@code requireCollation} 那一步，本条必红。</p>
     */
    @Test
    void mysqlProtocolWithoutTheRequiredCollationIsRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.verify(fakeSource("MySQL", "11.4.2-MariaDB", false)));
        assertTrue(ex.getMessage().contains(DatabaseVersionGuard.REQUIRED_COLLATION),
                "报错要点名缺的是哪条排序规则，实际：" + ex.getMessage());

        // 真 MySQL 但被人为裁掉排序规则（或未来某个精简发行版）同样拒
        assertThrows(IllegalStateException.class,
                () -> guard.verify(fakeSource("MySQL", "8.4.0", false)));
    }

    /**
     * 8.0.16 必须被拒——CHECK 在它上面确实生效，但 {@code utf8mb4_0900_bin} 要 8.0.17。
     *
     * <p>把 {@code MIN} 改回 {@code {8,0,16}}，本条必红。</p>
     */
    @Test
    void oneReleaseBelowFloorIsRejected() {
        // 排序规则一并给成「存在」，确保拒绝的理由确实是版本号那一道
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.verify(fakeSource("MySQL", "8.0.16", true)));
        assertTrue(ex.getMessage().contains("8.0.17"), "报错要说清门槛，实际：" + ex.getMessage());
        assertThrows(IllegalStateException.class,
                () -> guard.verify(fakeSource("MySQL", "5.7.44", true)));
    }

    /**
     * <b>fail closed</b>：确认是 MySQL 却解析不出版本号时必须拒绝启动。
     *
     * <p>「读不出来就当它没问题」正是本守卫要消灭的那类静默假设。
     * 把这一支改回 {@code log.warn + return}，本条必红。</p>
     */
    @Test
    void unparsableMysqlVersionFailsClosed() {
        assertThrows(IllegalStateException.class,
                () -> guard.verify(fakeSource("MySQL", "unknown", true)));
        assertThrows(IllegalStateException.class,
                () -> guard.verify(fakeSource("MySQL Community Server", null, true)));
    }

    /** 非 MySQL 只告警不拦：本守卫挡的是「已知会静默失效」的那一档，不是通用环境检查。 */
    @Test
    void nonMysqlIsNotBlocked() {
        assertDoesNotThrow(() -> guard.verify(fakeSource("PostgreSQL", "16.2", false)));
        assertDoesNotThrow(() -> guard.verify(fakeSource(null, null, false)));
    }

    // ---------- 夹具：DataSource → Connection → {DatabaseMetaData, PreparedStatement} 代理 ----------

    /**
     * @param product      {@code getDatabaseProductName()} 的返回值
     * @param version      {@code getDatabaseProductVersion()} 的返回值
     * @param hasCollation {@code information_schema.COLLATIONS} 里有没有那条排序规则
     */
    private static DataSource fakeSource(String product, String version, boolean hasCollation) {
        DatabaseMetaData meta = proxy(DatabaseMetaData.class, (p, m, a) -> {
            if ("getDatabaseProductName".equals(m.getName())) {
                return product;
            }
            if ("getDatabaseProductVersion".equals(m.getName())) {
                return version;
            }
            return defaultValue(m);
        });
        ResultSet rs = proxy(ResultSet.class, (p, m, a) -> {
            if ("next".equals(m.getName())) {
                return Boolean.TRUE;          // 只有一行：COUNT(*) 恒有结果
            }
            if ("getInt".equals(m.getName())) {
                return hasCollation ? 1 : 0;
            }
            return defaultValue(m);
        });
        PreparedStatement ps = proxy(PreparedStatement.class, (p, m, a) ->
                "executeQuery".equals(m.getName()) ? rs : defaultValue(m));
        Connection conn = proxy(Connection.class, (p, m, a) -> {
            if ("getMetaData".equals(m.getName())) {
                return meta;
            }
            if ("prepareStatement".equals(m.getName())) {
                return ps;
            }
            return defaultValue(m);
        });
        return proxy(DataSource.class, (p, m, a) ->
                "getConnection".equals(m.getName()) ? conn : defaultValue(m));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (p, m, a) -> {
                    // close() 等无返回值方法由 try-with-resources 调用，必须能静默通过
                    if ("close".equals(m.getName()) || "toString".equals(m.getName())
                            || "hashCode".equals(m.getName()) || "equals".equals(m.getName())) {
                        return switch (m.getName()) {
                            case "toString" -> type.getSimpleName() + "-fake";
                            case "hashCode" -> System.identityHashCode(p);
                            case "equals" -> p == (a == null ? null : a[0]);
                            default -> null;
                        };
                    }
                    return handler.invoke(p, m, a);
                });
    }

    /** 未打桩的方法返回类型对应的零值，避免 NPE 掩盖真正的断言。 */
    private static Object defaultValue(Method m) {
        Class<?> r = m.getReturnType();
        if (!r.isPrimitive()) {
            return null;
        }
        if (r == boolean.class) {
            return false;
        }
        if (r == void.class) {
            return null;
        }
        return 0;
    }
}
