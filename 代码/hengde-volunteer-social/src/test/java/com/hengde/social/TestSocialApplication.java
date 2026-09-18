package com.hengde.social;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 仅供测试的启动类。social 是无 main 的库，{@code @SpringBootTest} 需配置类加载上下文。
 *
 * @author hengde
 */
@SpringBootApplication(scanBasePackages = "com.hengde")
@MapperScan("com.hengde.**.dao")
public class TestSocialApplication {
}
