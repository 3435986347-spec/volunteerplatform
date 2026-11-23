package com.hengde.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JacksonConfig 序列化烟囱测试：锁死 LocalDateTime/LocalDate 输出格式与 Long→字符串。
 *
 * <p>防回归——手写 {@code new ObjectMapper()} 不会自动注册 jsr310 模块，漏注册时 VO 里的
 * {@code LocalDateTime} 会在 HTTP 序列化阶段抛异常（曾因此前端接口拿不到带时间字段的数据）。
 * 仅加载 {@link JacksonConfig} 单个配置，不启动 web/datasource，无需 Docker。</p>
 */
@SpringBootTest(classes = JacksonConfig.class)
class JacksonConfigTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void localDateTimeSerializesAsPattern() throws Exception {
        assertEquals("\"2026-06-07 10:30:00\"",
                objectMapper.writeValueAsString(LocalDateTime.of(2026, 6, 7, 10, 30, 0)));
    }

    @Test
    void localDateSerializesAsPattern() throws Exception {
        assertEquals("\"2026-06-07\"",
                objectMapper.writeValueAsString(LocalDate.of(2026, 6, 7)));
    }

    @Test
    void longSerializesAsString() throws Exception {
        assertEquals("\"123\"", objectMapper.writeValueAsString(123L));
    }
}
