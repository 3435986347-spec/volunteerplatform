package com.hengde.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private ObjectMapper objectMapper;
    private OperationLogInterceptor operationLogInterceptor;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Autowired
    public void setOperationLogInterceptor(OperationLogInterceptor operationLogInterceptor) {
        this.operationLogInterceptor = operationLogInterceptor;
    }

    /**
     * 操作日志拦截器（V4 系统治理批，Row 62）。注册在这里而不是 system 模块：
     * 「主动改变应用行为的配置放 api」——领域模块的测试上下文因此不会平白记一堆日志。
     */
    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(operationLogInterceptor).addPathPatterns("/**");
    }

    /**
     * Put the Jackson 2 converter before the default general JSON converter so
     * business responses keep the project ObjectMapper formats. Keep byte[] and
     * String converters ahead of it; Springdoc writes OpenAPI JSON as bytes, and
     * Jackson would otherwise serialize those bytes as a Base64 JSON string.
     */
    @Override
    @SuppressWarnings({"deprecation", "removal"})
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        MappingJackson2HttpMessageConverter jackson2Converter = new MappingJackson2HttpMessageConverter(objectMapper);
        int index = firstGeneralJsonConverterIndex(converters);
        if (index >= 0) {
            converters.add(index, jackson2Converter);
            return;
        }
        converters.add(jackson2Converter);
    }

    private int firstGeneralJsonConverterIndex(List<HttpMessageConverter<?>> converters) {
        for (int i = 0; i < converters.size(); i++) {
            HttpMessageConverter<?> converter = converters.get(i);
            if (converter instanceof ByteArrayHttpMessageConverter || converter instanceof StringHttpMessageConverter) {
                continue;
            }
            if (supportsJson(converter)) {
                return i;
            }
        }
        return -1;
    }

    private boolean supportsJson(HttpMessageConverter<?> converter) {
        return converter.getSupportedMediaTypes().stream().anyMatch(mediaType ->
                mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)
                        || mediaType.getSubtype().endsWith("+json"));
    }

    // CorsFilter 优先级高于 Sa-Token 拦截器，确保 OPTIONS 预检请求能正常通过
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOriginPattern("*");
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.addAllowedHeader("*");
        // 跨域时浏览器只让 JS 读到几个「简单响应头」，其余一律藏起来——不 expose 的话，
        // 后台控制台（本地打开、连服务器，是跨域）在 JS 里永远拿不到 TraceFilter 写的 X-Trace-Id，
        // 报错时也就没法把它展示给人去对日志。小程序的 wx.request 不受 CORS 约束，不受影响。
        config.addExposedHeader("X-Trace-Id");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
