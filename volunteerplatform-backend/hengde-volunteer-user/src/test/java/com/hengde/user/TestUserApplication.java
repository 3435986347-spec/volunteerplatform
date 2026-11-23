package com.hengde.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 仅供测试的启动类。
 *
 * <p>user 是无 main 的库，对外不提供 {@code @SpringBootApplication}；{@code @SpringBootTest} 需配置类加载上下文，
 * 故在 src/test 放此类。扫描 {@code com.hengde} 以纳入 common/auth/organization/activity 的 bean，
 * MapperScan 与 api 保持一致。</p>
 *
 * @author hengde
 */
@SpringBootApplication(scanBasePackages = "com.hengde")
@MapperScan("com.hengde.**.dao")
public class TestUserApplication {
}
