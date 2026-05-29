package com.hengde.auth;

import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 上下文加载冒烟测试。需本机有 Docker（Testcontainers 拉起 MySQL）。
 *
 * @author hengde
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class HengdeVolunteerAuthApplicationTests {

    @Test
    void contextLoads() {
    }
}
