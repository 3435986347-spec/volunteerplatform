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

    @Test
    void smsLogin_bannedAccount_rejectedBeforeToken() {
        String phone = "13700000004";
        insertPhoneVolunteer(phone, UserStatus.BANNED);
        authService.sendSmsCode(phone, SmsScene.LOGIN, null);
        String code = storedCode(SmsScene.LOGIN, phone);
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.smsLogin(phone, code, null));
        assertTrue(ex.getMessage().contains("禁用"));
    }

    @Test
    void smsLogin_cancelledAccount_rejected() {
        // 注销态(status=2)是终态，也应拒绝（口径：status≠NORMAL 一律拒，不止 BANNED）
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

    @Test
    void passwordLogin_bannedWithCorrectPassword_rejectedBeforeToken() {
        String phone = "13700000008";
        Volunteer v = insertPhoneVolunteer(phone, UserStatus.NORMAL);
        authService.setOrChangePassword(v.getId(), null, "right-pass-1");
        // 置禁用
        v.setStatus(UserStatus.BANNED);
        volunteerMapper.updateById(v);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.passwordLogin(phone, "right-pass-1", "10.0.0.3"));
        assertTrue(ex.getMessage().contains("禁用"));
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
