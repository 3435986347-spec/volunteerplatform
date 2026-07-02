package com.hengde.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Boot 4 / Spring 7 的 MVC 默认用 Jackson 3 序列化（JacksonJsonHttpMessageConverter），
     * 会忽略本项目配在 Jackson 2 {@code @Primary ObjectMapper}（JacksonConfig）上的日期格式与 Long→String，
     * 导致响应出现 ISO 'T'、Long 为数字。这里把基于该 ObjectMapper 的 Jackson-2 JSON 转换器<b>前置</b>为首选，
     * 使响应统一 {@code yyyy-MM-dd HH:mm:ss}、Long 为字符串；入参由该 mapper 的宽松反序列化兼容空格与 ISO 'T'。
     */
    @Override
    @SuppressWarnings({"deprecation", "removal"})
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(0, new MappingJackson2HttpMessageConverter(objectMapper));
    }

    // CorsFilter 优先级高于 Sa-Token 拦截器，确保 OPTIONS 预检请求能正常通过
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOriginPattern("*");
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
