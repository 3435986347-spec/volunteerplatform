package com.hengde.auth;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.sms.VerifyCodeService;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.common.utils.RedisUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证码滥用防护测试（M 安全加固）：
 * 校验失败次数上限作废、发送侧手机号日限量、来源 IP 限量、重发间隔。
 * 阈值经测试属性调小以便覆盖；各用例用不同手机号/IP，互不污染计数。
 * MySQL/Redis 由 Testcontainers 起。<b>需本机有 Docker。</b>
 *
 * @author hengde
 */
@SpringBootTest(properties = {
        "hengde.sms.verify-max-attempts=3",
        "hengde.sms.send-daily-cap-per-phone=2",
        "hengde.sms.send-hourly-cap-per-ip=3"
})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class VerifyCodeProtectionTest {

    @Autowired
    private VerifyCodeService verifyCodeService;

    @Autowired
    private RedisUtil redisUtil;

    /** 测试读取 Redis 里实际生成的验证码（与 VerifyCodeService 的 key 约定一致） */
    private String storedCode(String scene, String phone) {
        Object v = redisUtil.get("sms:code:" + scene + ":" + phone);
        return v == null ? null : v.toString();
    }

    @Test
    void verify_wrongAttemptsReachCap_codeInvalidated() {
        String phone = "13900000001";
        String scene = "test-attempt";
        verifyCodeService.sendCode(phone, scene);
        String correct = storedCode(scene, phone);

        // 前 cap-1 次填错：普通「验证码错误」
        for (int i = 0; i < 2; i++) {
            assertThrows(BusinessException.class, () -> verifyCodeService.verify(phone, scene, "000000"));
        }
        // 第 cap 次填错：作废
        BusinessException capEx = assertThrows(BusinessException.class,
                () -> verifyCodeService.verify(phone, scene, "000000"));
        assertTrue(capEx.getMessage().contains("次数过多"));
        // 作废后即便提交正确码也不通过
        assertThrows(BusinessException.class, () -> verifyCodeService.verify(phone, scene, correct));
    }

    @Test
    void verify_success_consumesCodeAndResetsCounter() {
        String phone = "13900000002";
        String scene = "test-consume";
        verifyCodeService.sendCode(phone, scene);
        String correct = storedCode(scene, phone);

        // 错一次后用正确码仍可通过（未达上限）
        assertThrows(BusinessException.class, () -> verifyCodeService.verify(phone, scene, "000000"));
        assertDoesNotThrow(() -> verifyCodeService.verify(phone, scene, correct));
        // 一次性消费：同码二次提交失败
        assertThrows(BusinessException.class, () -> verifyCodeService.verify(phone, scene, correct));
    }

    @Test
    void send_resendWithinInterval_rejected() {
        String phone = "13900000003";
        String scene = "test-resend";
        verifyCodeService.sendCode(phone, scene);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifyCodeService.sendCode(phone, scene));
        assertTrue(ex.getMessage().contains("频繁"));
    }

    @Test
    void send_phoneDailyCap_rejectedAcrossScenes() {
        String phone = "13900000004";
        // 不同场景避开同场景重发间隔；手机号日限量跨场景累计（cap=2）
        verifyCodeService.sendCode(phone, "test-cap-a");
        verifyCodeService.sendCode(phone, "test-cap-b");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifyCodeService.sendCode(phone, "test-cap-c"));
        assertTrue(ex.getMessage().contains("上限"));
    }

    @Test
    void send_ipHourlyCap_rejectedAcrossPhones() {
        String ip = "203.0.113.50";
        // 不同手机号绕不开 IP 限量（cap=3）
        verifyCodeService.sendCode("13900000005", "test-ip", ip);
        verifyCodeService.sendCode("13900000006", "test-ip", ip);
        verifyCodeService.sendCode("13900000007", "test-ip", ip);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifyCodeService.sendCode("13900000008", "test-ip", ip));
        assertTrue(ex.getMessage().contains("频繁"));
        // 未传 IP 的调用不受 IP 维度影响（兼容内部/无请求上下文场景）
        assertDoesNotThrow(() -> verifyCodeService.sendCode("13900000009", "test-ip"));
    }
}
