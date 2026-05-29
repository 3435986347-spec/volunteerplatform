package com.hengde.common.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 共享的 Testcontainers-Redis 测试基座（与 {@link TestcontainersConfig} 的 MySQL 分开）。
 *
 * <p>独立成一份的原因：引入 Redisson 后，{@code RedissonClient} 在 bean 创建时即会连 Redis，
 * 故凡用到分布式锁的模块（如 activity 报名）其 {@code @SpringBootTest} 必须有真实 Redis 后端；
 * 而 organization 等不用锁的模块不该被强制连 Redis。把 Redis 容器单列，按需 {@code @Import}：</p>
 * <pre>
 *   &#64;SpringBootTest
 *   &#64;Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
 *   class XxxServiceTest { ... }
 * </pre>
 *
 * <p>{@code @ServiceConnection(name = "redis")} 让 Spring Boot 把容器映射端口接管为
 * {@code spring.data.redis.*} 连接详情，Redisson starter 与 RedisTemplate 据此连上随机端口的容器。
 * <b>跑测试需本机有 Docker。</b></p>
 *
 * @author hengde
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainersConfig {

    @Bean
    @ServiceConnection(name = "redis")
    public GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
    }
}
