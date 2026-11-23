package com.hengde.auth;

import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.dto.AdminLoginDTO;
import com.hengde.auth.entity.AdminUser;
import com.hengde.auth.service.AdminAuthService;
import com.hengde.auth.service.LoginProtectionService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.common.utils.PasswordUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台登录防爆破测试（M 安全加固）：账号维度失败锁定（锁定后正确口令也拒绝）、
 * IP 维度喷洒失败上限、成功清零计数。阈值经测试属性调小；各用例用不同账号/IP 隔离计数。
 * 登录成功的发 token 路径依赖 web 上下文（Sa-Token），此处只覆盖锁定/清零语义，
 * 与 {@code VolunteerAuthServiceDevLoginTest} 的口径一致。
 * MySQL/Redis 由 Testcontainers 起。<b>需本机有 Docker。</b>
 *
 * @author hengde
 */
@SpringBootTest(properties = {
        "hengde.auth.login-max-failures=3",
        "hengde.auth.login-lock-seconds=300",
        "hengde.auth.login-ip-max-failures=5"
})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class AdminLoginProtectionTest {

    @Autowired
    private AdminAuthService adminAuthService;

    @Autowired
    private LoginProtectionService loginProtectionService;

    @Autowired
    private AdminUserMapper adminUserMapper;

    private void createAdmin(String username, String rawPassword) {
        AdminUser admin = new AdminUser();
        admin.setUsername(username);
        admin.setPassword(PasswordUtil.encrypt(rawPassword));
        admin.setRealName("测试管理员");
        admin.setIsSuperAdmin(0);
        admin.setStatus(0);
        adminUserMapper.insert(admin);
    }

    private AdminLoginDTO dto(String username, String password) {
        AdminLoginDTO dto = new AdminLoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }

    @Test
    void login_failuresReachCap_lockedEvenWithCorrectPassword() {
        String username = "lock-test-user";
        createAdmin(username, "right-pass-1");

        // 连错 3 次（阈值），每次都是普通「账号或密码错误」
        for (int i = 0; i < 3; i++) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> adminAuthService.login(dto(username, "wrong-pass"), "198.51.100.1"));
            assertTrue(ex.getMessage().contains("账号或密码错误"));
        }
        // 已锁定：正确口令也被拒（在密码校验之前拦截）
        BusinessException locked = assertThrows(BusinessException.class,
                () -> adminAuthService.login(dto(username, "right-pass-1"), "198.51.100.1"));
        assertTrue(locked.getMessage().contains("锁定"));
    }

    @Test
    void login_unknownUsername_alsoCountsAndLocks() {
        // 账号不存在同样计失败并可触发锁定（不向攻击者泄露账号是否存在）
        String username = "no-such-user";
        for (int i = 0; i < 3; i++) {
            assertThrows(BusinessException.class,
                    () -> adminAuthService.login(dto(username, "whatever"), "198.51.100.2"));
        }
        BusinessException locked = assertThrows(BusinessException.class,
                () -> adminAuthService.login(dto(username, "whatever"), "198.51.100.2"));
        assertTrue(locked.getMessage().contains("锁定"));
    }

    @Test
    void login_ipSprayAcrossUsernames_blockedByIpCap() {
        String ip = "198.51.100.3";
        // 换着账号喷洒：5 个不同账号各错一次（IP 上限 5）
        for (int i = 0; i < 5; i++) {
            final int n = i;
            assertThrows(BusinessException.class,
                    () -> adminAuthService.login(dto("spray-user-" + n, "x"), ip));
        }
        // 第 6 次（哪怕又换了账号）被 IP 维度拦下
        BusinessException locked = assertThrows(BusinessException.class,
                () -> adminAuthService.login(dto("spray-user-fresh", "x"), ip));
        assertTrue(locked.getMessage().contains("次数过多"));
    }

    @Test
    void protection_successClearsUserCounter() {
        String username = "clear-test-user";
        String ip = "198.51.100.4";
        loginProtectionService.onLoginFailed(username, ip);
        loginProtectionService.onLoginFailed(username, ip);
        assertDoesNotThrow(() -> loginProtectionService.checkNotLocked(username, ip));
        loginProtectionService.onLoginFailed(username, ip);
        assertThrows(BusinessException.class, () -> loginProtectionService.checkNotLocked(username, ip));
        // 成功清零账号计数后恢复可登录
        loginProtectionService.onLoginSucceeded(username);
        assertDoesNotThrow(() -> loginProtectionService.checkNotLocked(username, ip));
    }
}
