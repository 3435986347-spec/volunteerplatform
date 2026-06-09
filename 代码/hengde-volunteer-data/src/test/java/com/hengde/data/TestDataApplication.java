package com.hengde.data;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 仅供测试的启动类。data 是无 main 的库，{@code @SpringBootTest} 需配置类加载上下文。
 * 扫描 {@code com.hengde} 纳入 common/auth/organization/activity 的 bean，MapperScan 与 api 一致。
 *
 * @author hengde
 */
@SpringBootApplication(scanBasePackages = "com.hengde")
@MapperScan("com.hengde.**.dao")
public class TestDataApplication {
}
