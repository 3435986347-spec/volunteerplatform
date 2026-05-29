package com.hengde.api.config;

import com.hengde.auth.config.AuthProperties;
import com.hengde.common.crypto.SecurityProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 生产配置守卫：非「开发/测试」profile 启动时做 fail-fast 校验，
 * 拒绝仍在使用 dev 弱默认密钥/口令的配置，避免漏配环境变量就把弱密钥带上生产而静默裸奔。
 *
 * <p>放行条件：active profile 含 dev/test/local 之一。否则（含「未指定任何 profile」）按生产口径强校验：
 * AES/HMAC 密钥不得为空或仍是 dev 默认值；启用初始超管时其密码不得仍是 admin123。
 * 任一不满足即抛异常中断启动——宁可起不来，也不让弱配置上线。</p>
 *
 * <p>仅在 api（唯一可部署单元）的 Spring 上下文生效；领域模块测试 classpath 不含 api，不受影响。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class ProductionConfigGuard {

    private static final Set<String> DEV_PROFILES = Set.of("dev", "test", "local");
    private static final String DEV_AES_KEY = "dev-only-aes-secret-change-in-prod";
    private static final String DEV_HMAC_KEY = "dev-only-hmac-secret-change-in-prod";
    private static final String DEV_ADMIN_PASSWORD = "admin123";

    private Environment environment;
    private SecurityProperties securityProperties;
    private AuthProperties authProperties;

    @Autowired
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Autowired
    public void setSecurityProperties(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Autowired
    public void setAuthProperties(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @PostConstruct
    public void verify() {
        String[] active = environment.getActiveProfiles();
        boolean devLike = Arrays.stream(active).anyMatch(DEV_PROFILES::contains);
        if (devLike) {
            return;
        }
        if (active.length == 0) {
            throw new IllegalStateException(
                    "未指定运行 profile：生产请显式 --spring.profiles.active=prod，本地开发用 dev；"
                            + "拒绝以无 profile 方式启动，以免静默使用开发默认配置。");
        }
        List<String> problems = new ArrayList<>();
        if (isBlankOrDev(securityProperties.getAesKey(), DEV_AES_KEY)) {
            problems.add("SECURITY_AES_KEY 仍为开发默认值或为空");
        }
        if (isBlankOrDev(securityProperties.getHmacKey(), DEV_HMAC_KEY)) {
            problems.add("SECURITY_HMAC_KEY 仍为开发默认值或为空");
        }
        if (authProperties.isInitSuperAdmin() && DEV_ADMIN_PASSWORD.equals(authProperties.getSuperAdminPassword())) {
            problems.add("AUTH_SUPER_ADMIN_PASSWORD 仍为弱默认值 admin123");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("生产配置校验未通过（profile=" + Arrays.toString(active) + "）：\n - "
                    + String.join("\n - ", problems)
                    + "\n请通过环境变量提供真实密钥/口令后再启动。");
        }
        log.info("生产配置守卫校验通过，profile={}", Arrays.toString(active));
    }

    private boolean isBlankOrDev(String value, String devValue) {
        return value == null || value.isBlank() || devValue.equals(value);
    }
}
