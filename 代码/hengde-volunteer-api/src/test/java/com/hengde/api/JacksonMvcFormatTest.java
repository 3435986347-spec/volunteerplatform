package com.hengde.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengde.api.config.JacksonConfig;
import com.hengde.api.config.WebMvcConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 MVC 时间/Long 序列化：Boot4/Spring7 的 MVC 默认用 Jackson 3（忽略本项目配在 Jackson 2 上的格式）。
 * 这里直接构造两个配置对象验证修复，不启全量 app 上下文（无需外部 MySQL/Redis）：
 * ① {@link JacksonConfig} 的 ObjectMapper：LocalDateTime 输出空格无 T、入参兼容空格与 ISO 'T'、Long 输出字符串；
 * ② {@link WebMvcConfig#extendMessageConverters} 把基于该 mapper 的 Jackson-2 转换器前置为 JSON 首选。
 * Boot 会自动调用 WebMvcConfigurer.extendMessageConverters 并优先选首个 canWrite JSON 的转换器，故二者成立即 MVC 生效。
 *
 * @author hengde
 */
class JacksonMvcFormatTest {

    private final ObjectMapper mapper = new JacksonConfig().objectMapper();

    @Test
    void serializesLocalDateTimeSpaceFormatAndLongAsString() throws Exception {
        Sample s = new Sample();
        s.time = LocalDateTime.of(2026, 6, 10, 9, 0, 0);
        s.id = 123456789012345678L;
        String json = mapper.writeValueAsString(s);
        assertTrue(json.contains("2026-06-10 09:00:00"), "时间应为空格格式: " + json);
        assertFalse(json.contains("2026-06-10T"), "响应不应出现 ISO 'T': " + json);
        assertTrue(json.contains("\"123456789012345678\""), "Long 应序列化为字符串: " + json);
    }

    @Test
    void deserializesBothSpaceAndIsoInputs() throws Exception {
        LocalDateTime expected = LocalDateTime.of(2026, 6, 10, 9, 0, 0);
        assertEquals(expected, mapper.readValue("{\"time\":\"2026-06-10 09:00:00\"}", Sample.class).time, "空格入参");
        assertEquals(expected, mapper.readValue("{\"time\":\"2026-06-10T09:00:00\"}", Sample.class).time, "ISO 带秒入参");
        assertEquals(expected, mapper.readValue("{\"time\":\"2026-06-10T09:00\"}", Sample.class).time, "ISO 无秒入参(后台 datetime-local)");
    }

    @Test
    void webMvcConfigPrependsJacksonConverterUsingOurMapper() {
        WebMvcConfig cfg = new WebMvcConfig();
        cfg.setObjectMapper(mapper);
        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        converters.add(new StringHttpMessageConverter()); // 模拟 Boot 默认链中已有的转换器
        cfg.extendMessageConverters(converters);

        // MVC 选「首个 canWrite(JSON)」的转换器——应是我们前置(index 0)的 Jackson-2 转换器，且用我们的 mapper
        HttpMessageConverter<?> first = converters.stream()
                .filter(c -> c.canWrite(Sample.class, MediaType.APPLICATION_JSON))
                .findFirst().orElseThrow();
        assertTrue(first instanceof AbstractJackson2HttpMessageConverter,
                "MVC 首选 JSON 转换器应是 Jackson2（我们前置的），实际: " + first.getClass());
        assertSame(mapper, ((AbstractJackson2HttpMessageConverter) first).getObjectMapper(),
                "JSON 转换器应使用我们配置好的 ObjectMapper（空格日期 + Long 字符串）");
    }

    static class Sample {
        public LocalDateTime time;
        public Long id;
    }
}
