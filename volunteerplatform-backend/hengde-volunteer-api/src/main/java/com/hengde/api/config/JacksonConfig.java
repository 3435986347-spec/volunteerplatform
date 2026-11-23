package com.hengde.api.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.TimeZone;

@Configuration
public class JacksonConfig {

    private static final String DATE_TIME = "yyyy-MM-dd HH:mm:ss";
    private static final String DATE = "yyyy-MM-dd";
    private static final String TIME = "HH:mm:ss";

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        SimpleDateFormat sdf = new SimpleDateFormat(DATE_TIME);
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        mapper.setDateFormat(sdf);
        mapper.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);

        // Java 8 时间类型（LocalDateTime/LocalDate/LocalTime）：手建 ObjectMapper 不会自动注册 jsr310 模块，
        // 必须显式注册，否则 VO 里的 LocalDateTime 序列化会在运行期抛异常。格式与上面的 Date 对齐。
        DateTimeFormatter dt = DateTimeFormatter.ofPattern(DATE_TIME);
        DateTimeFormatter d = DateTimeFormatter.ofPattern(DATE);
        DateTimeFormatter t = DateTimeFormatter.ofPattern(TIME);
        JavaTimeModule javaTime = new JavaTimeModule();
        javaTime.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(dt));
        // 宽松反序列化：兼容前端送的空格格式与 ISO 'T'（后台 datetime-local 送 yyyy-MM-ddTHH:mm）
        javaTime.addDeserializer(LocalDateTime.class, new LenientLocalDateTimeDeserializer(dt));
        javaTime.addSerializer(LocalDate.class, new LocalDateSerializer(d));
        javaTime.addDeserializer(LocalDate.class, new LocalDateDeserializer(d));
        javaTime.addSerializer(LocalTime.class, new LocalTimeSerializer(t));
        javaTime.addDeserializer(LocalTime.class, new LocalTimeDeserializer(t));
        mapper.registerModule(javaTime);

        // 大整型（Long）序列化为字符串，避免前端 JS Number 丢精度（id 在前端按字符串处理）
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        mapper.registerModule(module);

        return mapper;
    }

    /**
     * 宽松 LocalDateTime 反序列化：先按项目固定 {@code yyyy-MM-dd HH:mm:ss} 解析，失败再用
     * {@link DateTimeFormatter#ISO_LOCAL_DATE_TIME}（兼容 {@code 2026-06-10T09:00} / {@code ...:00} / 带毫秒）。
     * 序列化输出仍统一空格格式（见 serializer），故响应无 T、入参两种都收。
     */
    private static class LenientLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        private final DateTimeFormatter spaceFormatter;

        LenientLocalDateTimeDeserializer(DateTimeFormatter spaceFormatter) {
            this.spaceFormatter = spaceFormatter;
        }

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String s = p.getValueAsString();
            if (s == null || s.isBlank()) {
                return null;
            }
            s = s.trim();
            try {
                return LocalDateTime.parse(s, spaceFormatter);
            } catch (DateTimeParseException e) {
                return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
        }
    }
}
