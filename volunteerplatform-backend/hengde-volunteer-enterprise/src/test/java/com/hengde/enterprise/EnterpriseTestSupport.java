package com.hengde.enterprise;

import cn.hutool.core.util.CreditCodeUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.sms.SmsScene;
import com.hengde.common.sms.VerifyCodeService;
import com.hengde.common.utils.RedisUtil;
import com.hengde.enterprise.dto.EnterpriseDTOs;
import org.junit.jupiter.api.function.Executable;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 爱心企业用例造数。
 *
 * <p><b>成功登录要 Sa-Token 的请求上下文</b>（1.43 起按线程绑定，由过滤器在每个请求里设置）：服务层用例用 {@link #inRequest} 挂它自带的模拟上下文。</p>
 *
 * @author hengde
 */
final class EnterpriseTestSupport {

    static final long ADMIN = 8801L;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 10_000_000L);

    private EnterpriseTestSupport() {
    }

    static long next() {
        return SEQ.incrementAndGet();
    }

    static String phone() {
        return "159" + String.format("%08d", next() % 100_000_000L);
    }

    static String username(String prefix) {
        return prefix + Long.toString(next(), 36);
    }

    /** hutool 生成的随机信用代码可能撞上别的用例生成过的，按调用次数拼不了，所以只能多试几次保证本用例内不重复。 */
    static String creditCode() {
        return CreditCodeUtil.randomCreditCode();
    }

    static String sendAndReadCode(VerifyCodeService verifyCodeService, RedisUtil redisUtil, String phone, String scene) {
        verifyCodeService.sendCode(phone, scene, null);
        Object v = redisUtil.get("sms:code:" + scene + ":" + phone);
        return v == null ? null : v.toString();
    }

    static EnterpriseDTOs.Register register(String username, String creditCode, String phone, String code) {
        EnterpriseDTOs.Register d = new EnterpriseDTOs.Register();
        d.setName("爱心企业" + next());
        d.setCreditCode(creditCode);
        d.setIntro("一家热心公益的企业");
        d.setAddress("雷州市西湖大道 1 号");
        d.setContactPhone("0759-8888888");
        d.setLeaderName("负责人" + next());
        d.setLeaderPhone(phone);
        d.setSmsCode(code);
        d.setUsername(username);
        d.setPassword("pass1234");
        return d;
    }

    static EnterpriseDTOs.Login login(String username, String password) {
        EnterpriseDTOs.Login d = new EnterpriseDTOs.Login();
        d.setUsername(username);
        d.setPassword(password);
        return d;
    }

    static <T> T inRequest(Callable<T> call) throws Exception {
        SaTokenContextMockUtil.setMockContext();
        try {
            return call.call();
        } finally {
            SaTokenContextMockUtil.clearContext();
        }
    }

    static void assertMessage(String fragment, Executable call) {
        BusinessException e = assertThrows(BusinessException.class, call);
        assertTrue(e.getMessage().contains(fragment), "期望提示含「" + fragment + "」，实际：" + e.getMessage());
    }

    static String registerScene() {
        return SmsScene.ENTERPRISE_REGISTER;
    }
}
