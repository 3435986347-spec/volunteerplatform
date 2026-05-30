package com.hengde.auth;

import com.hengde.auth.service.VolunteerAuthService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开发登录默认关闭的安全兜底：未显式开启 {@code hengde.auth.dev-login-enabled} 时，
 * {@code devLogin} 必须直接拒绝（在生成 token 之前），避免微信鉴权被绕过。
 * 启用后的正常发 token 路径依赖 web 上下文（同 {@code wechatLogin}），靠前端/手动联调验证。
 * MySQL 由 Testcontainers 起。<b>需本机有 Docker。</b>
 *
 * @author hengde
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class VolunteerAuthServiceDevLoginTest {

    @Autowired
    private VolunteerAuthService volunteerAuthService;

    @Test
    void devLogin_disabledByDefault_rejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> volunteerAuthService.devLogin("tester", false));
        assertTrue(ex.getMessage().contains("开发登录未启用"));
    }
}
