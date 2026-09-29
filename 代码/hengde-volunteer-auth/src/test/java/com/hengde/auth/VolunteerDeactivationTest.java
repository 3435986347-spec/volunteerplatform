package com.hengde.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.service.VolunteerAuthService;
import com.hengde.auth.service.VolunteerDeactivationService;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.sms.SmsScene;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.common.utils.RedisUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注销（7 天冷静期）与后台恢复账号。
 *
 * <p>协会 2026-09-16：注销＝退出协会；冷静期；注销后手机号释放、身份证不能再注册；想回来由理事会恢复。
 * 要钉住的：冷静期内账号照常可用、可撤销、手机号不释放；<b>到期按时间现算</b>——不等定时任务，
 * 第一次碰到（登录、按手机号查找）就落定并把号释放出来；落定只成一次；身份证不释放；恢复要换一个没被占用的号。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class VolunteerDeactivationTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 10_000_000L);

    @Autowired
    private VolunteerDeactivationService deactivationService;
    @Autowired
    private VolunteerAuthService authService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private RedisUtil redisUtil;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void coolingOff_keepsTheAccountUsable_andCanBeCancelled() {
        String phone = phone();
        Volunteer v = registered(phone);

        // 同一个号 60 秒内只能发一次码；错一次不作废，接着用同一个码
        authService.sendSmsCode(phone, SmsScene.ACCOUNT_DEACTIVATE, null);
        assertThrows(BusinessException.class, () -> deactivationService.request(v.getId(), "000000"), "验证码错不许注销");
        assertNull(reload(v.getId()).getDeactivateEffectiveTime(), "验证码错时什么也没改");

        LocalDateTime before = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime effective = deactivationService.request(v.getId(), code(SmsScene.ACCOUNT_DEACTIVATE, phone));
        assertFalse(effective.isBefore(before.plusDays(7)), "冷静期 7 天");

        Volunteer during = reload(v.getId());
        assertEquals(UserStatus.NORMAL, during.getStatus(), "冷静期内照常可用");
        assertEquals(cryptoUtil.hashPhone(phone), during.getPhoneHash(), "冷静期内手机号不释放");
        assertEquals(effective, during.getDeactivateEffectiveTime());
        assertTrue(deactivationService.status(v.getId()).pending());
        assertFalse(deactivationService.finalizeIfDue(v.getId()), "没到期不落定");
        assertThrows(BusinessException.class, () -> deactivationService.request(v.getId(), "123456"), "不能重复申请");

        deactivationService.cancel(v.getId());
        Volunteer cancelled = reload(v.getId());
        assertNull(cancelled.getDeactivateEffectiveTime());
        assertNull(cancelled.getDeactivateRequestTime());
        assertEquals(UserStatus.NORMAL, cancelled.getStatus());
        assertThrows(BusinessException.class, () -> deactivationService.cancel(v.getId()), "没有待生效的申请就没得撤销");
    }

    /** 到期后第一次按手机号碰到它就落定：号释放、身份证不释放、openid 换掉；同号可以重新登录建一个新账号。 */
    @Test
    void afterCoolingOff_theFirstLookupFinalizes_andReleasesThePhoneNotTheIdCard() {
        String phone = phone();
        Volunteer v = registered(phone);
        makeDue(v.getId());

        // 用同一个号验证码登录：老账号当场落定、号释放，陌生号自动建一个新的游客账号
        authService.sendSmsCode(phone, SmsScene.LOGIN, null);
        String code = code(SmsScene.LOGIN, phone);
        try {
            authService.smsLogin(phone, code, null);
        } catch (Exception ignored) {
            // 非 web 上下文 StpUtil.login 抛错；落定与建号都在它之前完成（VolunteerPasswordAuthTest 同一绕法）
        }

        Volunteer old = reload(v.getId());
        assertEquals(UserStatus.DELETED, old.getStatus(), "到期即注销");
        assertNull(old.getPhone());
        assertNull(old.getPhoneHash(), "手机号释放");
        assertNull(old.getPassword());
        assertEquals(VolunteerDeactivationService.DEACTIVATED_OPENID_PREFIX + v.getId(), old.getOpenid(),
                "合成 openid 换掉，否则同号重新建号会撞 openid 唯一键");
        assertEquals(cryptoUtil.hashIdCard(idCardOf(v)), old.getIdCardHash(), "身份证不释放——不能再注册");
        assertNotNull(old.getDeactivatedTime());

        Volunteer fresh = volunteerMapper.selectList(Wrappers.<Volunteer>lambdaQuery()
                .eq(Volunteer::getPhoneHash, cryptoUtil.hashPhone(phone))).stream().findFirst().orElse(null);
        assertNotNull(fresh, "号释放出来后同号可以重新建号");
        assertNotEquals(v.getId(), fresh.getId());
        assertNull(fresh.getRegisterTime(), "新账号是游客，要重新实名（身份证那一关挡着）");
    }

    @Test
    void dueAccount_isRejectedAtLogin_andCannotBeCancelledAnyMore() {
        String phone = phone();
        Volunteer v = registered(phone);
        makeDue(v.getId());

        assertThrows(BusinessException.class, () -> deactivationService.cancel(v.getId()));
        assertEquals(UserStatus.DELETED, reload(v.getId()).getStatus(), "到期后想撤销也晚了，当场落定");
        assertFalse(deactivationService.finalizeIfDue(v.getId()), "已经落定的不会再落定一次");
    }

    /** 多处同时碰到一个到期账号（请求闸门、登录、定时任务），落定只成一次。 */
    @Test
    void finalizingTheSameAccountConcurrently_happensExactlyOnce() throws Exception {
        Volunteer v = registered(phone());
        makeDue(v.getId());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return deactivationService.finalizeIfDue(v.getId());
                }));
            }
            go.countDown();
            int wins = 0;
            for (Future<Boolean> f : results) {
                wins += f.get() ? 1 : 0;
            }
            assertEquals(1, wins);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void finalizeAllDue_collectsAccountsNobodyTouched() {
        Volunteer due1 = registered(phone());
        Volunteer due2 = registered(phone());
        Volunteer cooling = registered(phone());
        makeDue(due1.getId());
        makeDue(due2.getId());
        jdbc.update("UPDATE volunteer SET deactivate_request_time = NOW(), "
                + "deactivate_effective_time = DATE_ADD(NOW(), INTERVAL 3 DAY) WHERE id = ?", cooling.getId());

        assertTrue(deactivationService.finalizeAllDue() >= 2);
        assertEquals(UserStatus.DELETED, reload(due1.getId()).getStatus());
        assertEquals(UserStatus.DELETED, reload(due2.getId()).getStatus());
        assertEquals(UserStatus.NORMAL, reload(cooling.getId()).getStatus(), "还在冷静期的不动");
    }

    /** 恢复：只收已注销的；手机号被占用要换一个；恢复后账号正常、换回按手机号合成的 openid。 */
    @Test
    void restore_needsAFreePhone_andBringsTheAccountBack() {
        String phone = phone();
        Volunteer v = registered(phone);
        assertThrows(BusinessException.class, () -> deactivationService.restore(v.getId(), phone(), 900L),
                "没注销的账号谈不上恢复");
        makeDue(v.getId());
        assertTrue(deactivationService.finalizeIfDue(v.getId()));

        // 原号已被别人注册了
        registered(phone);
        assertEquals("这个手机号已被其他账号使用，请换一个",
                assertThrows(BusinessException.class, () -> deactivationService.restore(v.getId(), phone, 900L)).getMessage());
        assertThrows(BusinessException.class, () -> deactivationService.restore(v.getId(), "12345", 900L), "手机号格式");

        String newPhone = phone();
        deactivationService.restore(v.getId(), newPhone, 900L);
        Volunteer back = reload(v.getId());
        assertEquals(UserStatus.NORMAL, back.getStatus());
        assertEquals(cryptoUtil.hashPhone(newPhone), back.getPhoneHash());
        assertEquals(newPhone, cryptoUtil.decrypt(back.getPhone()));
        assertEquals("p:" + cryptoUtil.hashPhone(newPhone).substring(0, 62), back.getOpenid());
        assertEquals(900L, back.getRestoreBy());
        assertNotNull(back.getRestoreTime());
        assertNull(back.getDeactivateEffectiveTime(), "恢复后不再挂着注销申请");
        assertNotNull(back.getRegisterTime(), "实名信息原样保留");
        assertThrows(BusinessException.class, () -> deactivationService.restore(v.getId(), phone(), 900L), "恢复过就不能再恢复");
    }

    /** 到期账号手里的号，被别人换绑 / 注册查重时也要先释放——不等有人碰巧登录那个老账号。 */
    @Test
    void releasePhoneIfDue_freesTheNumberForSomeoneElse() {
        String phone = phone();
        Volunteer v = registered(phone);
        String hash = cryptoUtil.hashPhone(phone);

        deactivationService.releasePhoneIfDue(hash);
        assertEquals(hash, reload(v.getId()).getPhoneHash(), "没申请注销的不动");

        jdbc.update("UPDATE volunteer SET deactivate_request_time = NOW(), "
                + "deactivate_effective_time = DATE_ADD(NOW(), INTERVAL 3 DAY) WHERE id = ?", v.getId());
        deactivationService.releasePhoneIfDue(hash);
        assertEquals(hash, reload(v.getId()).getPhoneHash(), "冷静期内不释放");

        makeDue(v.getId());
        deactivationService.releasePhoneIfDue(hash);
        assertNull(reload(v.getId()).getPhoneHash(), "到期就释放");
    }

    // ---------------- helpers ----------------

    private void makeDue(Long id) {
        jdbc.update("UPDATE volunteer SET deactivate_request_time = DATE_SUB(NOW(), INTERVAL 8 DAY), "
                + "deactivate_effective_time = DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE id = ?", id);
    }

    private Volunteer registered(String phone) {
        long n = SEQ.incrementAndGet();
        String idCard = "4401" + String.format("%010d", n) + "123X";
        Volunteer v = new Volunteer();
        v.setOpenid("p:" + cryptoUtil.hashPhone(phone).substring(0, 62));
        v.setRealName("注销测试" + n);
        v.setPhone(cryptoUtil.encrypt(phone));
        v.setPhoneHash(cryptoUtil.hashPhone(phone));
        v.setIdCardNo(cryptoUtil.encrypt(idCard));
        v.setIdCardHash(cryptoUtil.hashIdCard(idCard));
        v.setPassword("x");
        v.setStatus(UserStatus.NORMAL);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v;
    }

    private String idCardOf(Volunteer v) {
        return cryptoUtil.decrypt(volunteerMapper.selectById(v.getId()).getIdCardNo());
    }

    private Volunteer reload(Long id) {
        return volunteerMapper.selectById(id);
    }

    private String code(String scene, String phone) {
        Object v = redisUtil.get("sms:code:" + scene + ":" + phone);
        return v == null ? null : v.toString();
    }

    private static String phone() {
        return "136" + String.format("%08d", SEQ.incrementAndGet() % 100_000_000L);
    }
}
