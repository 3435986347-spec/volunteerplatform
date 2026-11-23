package com.hengde.auth;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.service.VolunteerAuthService;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.sms.SmsScene;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.common.utils.PasswordUtil;
import com.hengde.common.utils.RedisUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 志愿者「手机号体系」登录测试（V20 密码 + 验证码登录 + 设/改密 + 账密登录 + 防爆破）。
 *
 * <p>约定：成功发 token 路径调 {@code StpUtil.login} 需 web 上下文（同 {@code VolunteerAuthServiceDevLoginTest}），
 * 本测试覆盖<b>拒绝/纯逻辑</b>路径与自动建号（建号在 login 之前完成，可在 DB 验证）。
 * Testcontainers 起 MySQL（Flyway 跑到 V20，即验证 V20 空库迁移与已有 uk_phone_hash 不冲突）+ Redis。
 * <b>需本机有 Docker。</b></p>
 *
 * @author hengde
 */
@SpringBootTest(properties = {
        "hengde.auth.login-max-failures=3",
        "hengde.auth.login-lock-seconds=300",
        "hengde.auth.login-ip-max-failures=20"
})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class VolunteerPasswordAuthTest {

    @Autowired
    private VolunteerAuthService authService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private RedisUtil redisUtil;

    /** 建一个已绑手机号的志愿者（游客或已实名由 registered 决定） */
    private Volunteer insertPhoneVolunteer(String phone, int status) {
        Volunteer v = new Volunteer();
        v.setPhone(cryptoUtil.encrypt(phone));
        v.setPhoneHash(cryptoUtil.hashPhone(phone));
        v.setOpenid("p:" + cryptoUtil.hashPhone(phone).substring(0, 62));
        v.setStatus(status);
        volunteerMapper.insert(v);
        return v;
    }

    private Volunteer reload(Long id) {
        return volunteerMapper.selectById(id);
    }

    private String storedCode(String scene, String phone) {
        Object v = redisUtil.get("sms:code:" + scene + ":" + phone);
        return v == null ? null : v.toString();
    }

    // ---------- V20：password 列可读写（迁移成功的直接证据） ----------

    @Test
    void v20_passwordColumn_persists() {
        Volunteer v = insertPhoneVolunteer("13700000000", UserStatus.NORMAL);
        authService.setOrChangePassword(v.getId(), null, "init-pass-1");
        assertTrue(PasswordUtil.matches("init-pass-1", reload(v.getId()).getPassword()));
    }

    // ---------- 发码场景白名单 ----------

    @Test
    void sendSmsCode_unknownScene_rejected() {
        assertThrows(BusinessException.class, () -> authService.sendSmsCode("13700000001", "unknown-scene", null));
        // 白名单内场景放行（短信未启用时只写 Redis，不真发）
        assertDoesNotThrow(() -> authService.sendSmsCode("13700000001", SmsScene.LOGIN, null));
        assertDoesNotThrow(() -> authService.sendSmsCode("13700000001", SmsScene.CHANGE_PHONE, null));
    }

    // ---------- 验证码登录 ----------

    @Test
    void smsLogin_wrongCode_rejected() {
        authService.sendSmsCode("13700000002", SmsScene.LOGIN, null);
        assertThrows(BusinessException.class, () -> authService.smsLogin("13700000002", "000000", null));
    }

    @Test
    void smsLogin_freshPhone_autoCreatesGuest() {
        String phone = "13700000003";
        authService.sendSmsCode(phone, SmsScene.LOGIN, null);
        String code = storedCode(SmsScene.LOGIN, phone);
        // 验证码正确 → 自动建号后调 StpUtil.login（非 web 上下文会抛错）；建号在 login 之前完成
        try {
            authService.smsLogin(phone, code, null);
        } catch (Exception ignored) {
            // 非 web 上下文 StpUtil.login 抛错属预期，账号此前已建好
        }
        Volunteer created = volunteerMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Volunteer>lambdaQuery()
                        .eq(Volunteer::getPhoneHash, cryptoUtil.hashPhone(phone))).stream().findFirst().orElse(null);
        assertNotNull(created, "陌生手机号应自动建号");
        assertTrue(created.getOpenid().startsWith("p:"), "合成 openid 前缀");
        assertTrue(created.getOpenid().length() <= 64, "openid 不超 VARCHAR(64)");
        assertNull(created.getRegisterTime(), "自动建号为游客态");
    }

    /**
     * 禁用账号<b>照常发 token</b>——协会 2026-08-11 第 5 条「只给禁用账号开个小口子、
     * 只能看奖惩和提申诉」。挡在登录这一步，那个口子就无从谈起。
     *
     * <p><b>🔁 本用例原先断言的是相反的行为</b>（原名 {@code ..._rejectedBeforeToken}）。
     * 拿到 token 之后能做什么由 {@code BannedAccountGate.EXEMPT_PATHS} 决定：
     * <b>「登录成功」从此不再等于「账号可用」</b>。</p>
     *
     * <p><b>为什么断言的是「抛的不是 BusinessException」</b>：非 web 上下文里
     * {@code StpUtil.login} 必然抛错（本类抬头已说明，既有用例都这么绕），拿不到 token 本身。
     * 但能走到那一步就说明业务校验放行了——若 {@code ensureLoginable} 还在拦，
     * 抛的会是 {@code BusinessException("账号已被禁用")}，且发生在 {@code StpUtil.login} 之前。</p>
     */
    @Test
    void smsLogin_bannedAccount_stillGetsToken() {
        String phone = "13700000004";
        insertPhoneVolunteer(phone, UserStatus.BANNED);
        authService.sendSmsCode(phone, SmsScene.LOGIN, null);
        String code = storedCode(SmsScene.LOGIN, phone);
        Exception ex = assertThrows(Exception.class, () -> authService.smsLogin(phone, code, null));
        assertFalse(ex instanceof BusinessException,
                "禁用账号必须走到 StpUtil.login（非 web 上下文才抛的那个错），"
                        + "而不是在 ensureLoginable 就被拒。实际：" + ex);
    }

    @Test
    void smsLogin_cancelledAccount_rejected() {
        // 注销态(status=2)仍然拒发 token——协会开的口子只针对「禁用」。
        // 注销的人已经走了：没有处罚要看，也没有申诉要提。
        String phone = "13700000012";
        insertPhoneVolunteer(phone, UserStatus.DELETED);
        authService.sendSmsCode(phone, SmsScene.LOGIN, null);
        String code = storedCode(SmsScene.LOGIN, phone);
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.smsLogin(phone, code, null));
        assertTrue(ex.getMessage().contains("注销"));
    }

    @Test
    void smsLogin_legacyDeletedRowSamePhone_businessErrorNotServerError() {
        // 残留的旧逻辑删除行仍占着 uk_phone_hash（未释放唯一字段）→ 重登该号应得明确业务错误，而非 500
        String phone = "13700000013";
        Volunteer v = insertPhoneVolunteer(phone, UserStatus.NORMAL);
        volunteerMapper.deleteById(v.getId());   // 逻辑删除但不释放 phone_hash（模拟旧数据）
        authService.sendSmsCode(phone, SmsScene.LOGIN, null);
        String code = storedCode(SmsScene.LOGIN, phone);
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.smsLogin(phone, code, null));
        assertTrue(ex.getMessage().contains("暂不可用"));
    }

    @Test
    void smsLogin_afterReleasedDelete_canReCreate() {
        // 删除时释放唯一字段（openid 改 deleted:、phone_hash 置 null）后，同手机号可重新建号（可重注册）
        String phone = "13700000014";
        Volunteer v = insertPhoneVolunteer(phone, UserStatus.NORMAL);
        volunteerMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<Volunteer>lambdaUpdate()
                .eq(Volunteer::getId, v.getId())
                .set(Volunteer::getOpenid, "deleted:" + v.getId())
                .set(Volunteer::getPhoneHash, null));
        volunteerMapper.deleteById(v.getId());
        authService.sendSmsCode(phone, SmsScene.LOGIN, null);
        String code = storedCode(SmsScene.LOGIN, phone);
        try {
            authService.smsLogin(phone, code, null);
        } catch (Exception ignored) {
            // StpUtil.login 非 web 上下文抛错；新账号在此前已建好
        }
        Volunteer recreated = volunteerMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Volunteer>lambdaQuery()
                        .eq(Volunteer::getPhoneHash, cryptoUtil.hashPhone(phone))).stream().findFirst().orElse(null);
        assertNotNull(recreated, "释放唯一字段后同号应能重新建号");
        assertTrue(recreated.getOpenid().startsWith("p:"));
    }

    // ---------- 设置/修改密码 ----------

    @Test
    void setOrChangePassword_firstSetThenChange() {
        Volunteer v = insertPhoneVolunteer("13700000005", UserStatus.NORMAL);
        authService.setOrChangePassword(v.getId(), null, "pass-aaa-1");
        assertTrue(PasswordUtil.matches("pass-aaa-1", reload(v.getId()).getPassword()));
        // 改密：原密码错 → 拒绝
        assertThrows(BusinessException.class, () -> authService.setOrChangePassword(v.getId(), "wrong", "pass-bbb-1"));
        // 原密码对 → 通过
        authService.setOrChangePassword(v.getId(), "pass-aaa-1", "pass-bbb-1");
        assertTrue(PasswordUtil.matches("pass-bbb-1", reload(v.getId()).getPassword()));
    }

    @Test
    void setOrChangePassword_noPhone_rejected() {
        Volunteer v = new Volunteer();
        v.setOpenid("wx-no-phone-1");
        v.setStatus(UserStatus.NORMAL);
        volunteerMapper.insert(v);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.setOrChangePassword(v.getId(), null, "x123456"));
        assertTrue(ex.getMessage().contains("手机号"));
    }

    // ---------- 账密登录 ----------

    @Test
    void passwordLogin_noAccountOrWrongPassword_uniformError() {
        // 无账号
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> authService.passwordLogin("13700000006", "whatever", "10.0.0.1"));
        assertTrue(e1.getMessage().contains("手机号或密码错误"));
        // 有账号但未设密码
        insertPhoneVolunteer("13700000007", UserStatus.NORMAL);
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> authService.passwordLogin("13700000007", "whatever", "10.0.0.2"));
        assertTrue(e2.getMessage().contains("手机号或密码错误"));
    }

    /**
     * 禁用账号 + 正确密码：<b>照常发 token</b>，<b>并且清掉防爆破的失败计数</b>。
     *
     * <p>第二件事是本用例真正要钉的。{@code passwordLogin} 里三句的<b>顺序</b>是
     * {@code ensureLoginable} → {@code onVolunteerLoginSucceeded} → {@code StpUtil.login}：
     * 禁用账号从前会在第一句被拒，于是计数既不加也不清——那不是一条写下来的口径，
     * 而是顺序的副产品。现在第一句放行了，副产品也跟着变了，得有用例说明这是对的：
     * <b>密码对了就是登录成功，计数本就该清</b>。</p>
     *
     * <p>怎么观察「清掉了」：阈值是 3（见类上的 {@code login-max-failures=3}）。
     * 先错 2 次，再用正确密码登录一次，再错 2 次——总共错了 4 次。
     * 若那一次成功没有清零，第 3 次失败就会把账号锁上，最后一次拿到的会是锁定文案。</p>
     */
    @Test
    void passwordLogin_bannedWithCorrectPassword_stillSucceedsAndClearsFailureCount() {
        String phone = "13700000008";
        Volunteer v = insertPhoneVolunteer(phone, UserStatus.NORMAL);
        authService.setOrChangePassword(v.getId(), null, "right-pass-1");
        v.setStatus(UserStatus.BANNED);
        volunteerMapper.updateById(v);

        for (int i = 0; i < 2; i++) {
            assertThrows(BusinessException.class,
                    () -> authService.passwordLogin(phone, "wrong", "10.0.0.3"));
        }

        Exception ex = assertThrows(Exception.class,
                () -> authService.passwordLogin(phone, "right-pass-1", "10.0.0.3"));
        assertFalse(ex instanceof BusinessException,
                "禁用账号 + 正确密码必须走到 StpUtil.login，而不是在 ensureLoginable 就被拒。实际：" + ex);

        BusinessException last = null;
        for (int i = 0; i < 2; i++) {
            last = assertThrows(BusinessException.class,
                    () -> authService.passwordLogin(phone, "wrong", "10.0.0.3"));
        }
        assertTrue(last.getMessage().contains("手机号或密码错误"),
                "那次成功登录应已清零失败计数，累计 4 次错误不该触发锁定。实际：" + last.getMessage());
    }

    @Test
    void passwordLogin_bruteForce_locksAfterCap() {
        String phone = "13700000009";
        // 连错 3 次（阈值），每次普通错误
        for (int i = 0; i < 3; i++) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> authService.passwordLogin(phone, "x", "10.0.0.4"));
            assertTrue(ex.getMessage().contains("手机号或密码错误"));
        }
        // 第 4 次：被账号维度锁定拦下
        BusinessException locked = assertThrows(BusinessException.class,
                () -> authService.passwordLogin(phone, "x", "10.0.0.4"));
        assertTrue(locked.getMessage().contains("锁定"));
    }

    // ---------- 忘记密码 ----------

    @Test
    void resetPasswordBySms_success() {
        String phone = "13700000010";
        Volunteer v = insertPhoneVolunteer(phone, UserStatus.NORMAL);
        authService.sendSmsCode(phone, SmsScene.VOLUNTEER_PASSWORD_RESET, null);
        String code = storedCode(SmsScene.VOLUNTEER_PASSWORD_RESET, phone);
        authService.resetPasswordBySms(phone, code, "reset-pass-1");
        assertTrue(PasswordUtil.matches("reset-pass-1", reload(v.getId()).getPassword()));
    }

    @Test
    void resetPasswordBySms_unregisteredPhone_rejected() {
        String phone = "13700000011";
        authService.sendSmsCode(phone, SmsScene.VOLUNTEER_PASSWORD_RESET, null);
        String code = storedCode(SmsScene.VOLUNTEER_PASSWORD_RESET, phone);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.resetPasswordBySms(phone, code, "reset-pass-2"));
        assertTrue(ex.getMessage().contains("未注册"));
    }
}
