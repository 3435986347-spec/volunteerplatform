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

    @Bean
    @ServiceConnection
    public MySQLContainer<?> mysqlContainer() {
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.0"));
    }
}
