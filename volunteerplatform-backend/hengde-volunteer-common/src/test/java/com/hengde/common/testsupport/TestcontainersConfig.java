package com.hengde.common.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 共享的 Testcontainers-MySQL 测试基座。
 *
 * <p>各领域模块的 {@code @SpringBootTest} 通过 {@code @Import(TestcontainersConfig.class)}
 * 复用这一份 MySQL 容器配置，避免每个模块各写一遍。{@code @ServiceConnection} 会把容器的
 * 连接信息自动接管为数据源（无需手写 datasource url/账号），Flyway 也会在该容器库里跑迁移。</p>
 *
 * <p>用法（在领域模块的 src/test 里）：</p>
 * <pre>
 *   &#64;SpringBootTest
 *   &#64;Import(TestcontainersConfig.class)
 *   class XxxServiceTest { ... }
 * </pre>
 *
 * <p>消费方需在自己的 test 依赖里加 {@code hengde-volunteer-common:test-jar}，
 * 并自带 {@code spring-boot-testcontainers} 与 {@code org.testcontainers:mysql}（test-jar 的
 * 依赖不会传递）。<b>跑测试需本机有 Docker。</b>不用 H2——MyBatis-Plus 走 MySQL 方言、
 * flyway-mysql 迁移不兼容 H2。</p>
 *
 * @author hengde
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    /**
     * <b>钉死补丁版本，不用浮动的 {@code mysql:8.0}</b>。
     *
     * <p>迁移里有把 {@code CHECK} 当作硬防线的地方（V32 的 {@code ck_rp_sanction_days}、
     * V33 的 {@code ck_rp_sanction_days_cap}），而 <b>MySQL 8.0.16 之前会解析 CHECK 却不执行</b>——
     * 在那种服务器上约束形同注释、相关用例会「通过」，而通过的原因是约束根本没生效。
     * 浮动标签意味着某天镜像变了、行为跟着变，且没有任何提示。</p>
     *
     * <p>{@link #MIN_SUPPORTED} 是本项目的最低支持版本（部署侧同此口径，见 {@code 部署/} 与 README）；
     * 这里固定的 {@link #IMAGE} 必须不低于它。升级镜像时一并确认 CHECK 与
     * {@code utf8mb4_0900_bin} 排序规则这两类特性仍然可用。</p>
     */
    public static final String IMAGE = "mysql:8.0.46";

    /**
     * 本项目要求的最低 MySQL 版本，与 {@code DatabaseVersionGuard.MIN_VERSION} 同一口径。
     *
     * <p>由两条硬前提取大得到：<b>8.0.16</b> 起 CHECK 约束才真正执行；
     * <b>8.0.17</b> 起才有 {@code utf8mb4_0900_bin} 排序规则（V34 靠它，8.0.16 上会
     * {@code ERROR 1273 Unknown collation}，已在 {@code mysql:8.0.16} 容器实测）。</p>
     */
    public static final String MIN_SUPPORTED = "8.0.17";

    @Bean
    @ServiceConnection
    public MySQLContainer<?> mysqlContainer() {
        return new MySQLContainer<>(DockerImageName.parse(IMAGE));
    }
}
