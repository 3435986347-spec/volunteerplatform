package com.hengde.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置。
 *
 * <p>Spring Boot 默认装配的 {@link RedisTemplate} 用 JDK 序列化，存进 Redis 的 key/value
 * 会变成一串二进制乱码，用 redis-cli 根本看不懂。这里换成：</p>
 * <ul>
 *     <li>key：纯字符串（{@link StringRedisSerializer}）；</li>
 *     <li>value：JSON（{@link GenericJackson2JsonRedisSerializer}），存对象时自动转 JSON，取出时自动还原。</li>
 * </ul>
 *
 *
 * @author hengde
 */
@Configuration
public class RedisConfig {

    /**
     * 自定义 RedisTemplate，供 {@link com.hengde.common.utils.RedisUtil} 注入使用。
     *
     * @param connectionFactory Redis 连接工厂，由 Spring Boot 根据配置自动创建后注入进来
     * @return 配置好序列化方式的 RedisTemplate
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();

        // 普通 key-value：key 用字符串，value 用 JSON
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        // Hash 结构的 field 和 value 同理
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
