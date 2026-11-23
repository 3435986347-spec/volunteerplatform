package com.hengde.auth;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.service.VolunteerAuthService;
import com.hengde.auth.constant.SanctionScope;
import com.hengde.auth.service.SanctionService;
import com.hengde.auth.service.VolunteerDeactivationService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.LoginVO;
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
    @Autowired
    private VolunteerQueryService queryService;
    @Autowired
    private SanctionService sanctionService;

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

    /**
     * 协会答复五 乙-4（2026-10-16）：「7天冷静期即退出账号登录。再次登录时显示注销冷静期中。如登录则自动打断注销流程，账号恢复正常」。
     *
     * <p>申请即踢下线；冷静期里任何一种登录都撤销注销，登录结果带 {@code deactivationCancelled=true} 让小程序提示他；
     * 找回密码不是登录，不打断。</p>
     */
    @Test
    void requestLogsOut_andLoggingBackInDuringCoolingOffCancelsIt() {
        String phone = phone();
        Volunteer v = registered(phone);
        SaTokenContextMockUtil.setMockContext(() -> {
            StpUtil.login(v.getId());
            String token = StpUtil.getTokenValue();
            authService.sendSmsCode(phone, SmsScene.ACCOUNT_DEACTIVATE, null);
            deactivationService.request(v.getId(), code(SmsScene.ACCOUNT_DEACTIVATE, phone));
            assertNull(StpUtil.getLoginIdByToken(token), "申请即退出登录（所有设备）");
            assertTrue(deactivationService.status(v.getId()).pending());

            authService.sendSmsCode(phone, SmsScene.VOLUNTEER_PASSWORD_RESET, null);
            authService.resetPasswordBySms(phone, code(SmsScene.VOLUNTEER_PASSWORD_RESET, phone), "pass12345");
            assertTrue(deactivationService.status(v.getId()).pending(), "找回密码不是登录，不打断注销");

            authService.sendSmsCode(phone, SmsScene.LOGIN, null);
            LoginVO login = authService.smsLogin(phone, code(SmsScene.LOGIN, phone), null);
            assertTrue(login.isDeactivationCancelled(), "登录结果告诉小程序：这次登录撤销了注销");
            assertFalse(deactivationService.status(v.getId()).pending(), "账号恢复正常");
            assertEquals(UserStatus.NORMAL, reload(v.getId()).getStatus());

            LoginVO again = authService.passwordLogin(phone, "pass12345", null);
            assertFalse(again.isDeactivationCancelled(), "已经撤销过，再登录没什么可撤");
        });
    }

    /** 冷静期已过再登录：不是撤销，而是当场落定、报「账号已注销」。 */
    @Test
    void loginAfterCoolingOff_finalizesInsteadOfCancelling() {
        String phone = phone();
        Volunteer v = registered(phone);
        makeDue(v.getId());
        SaTokenContextMockUtil.setMockContext(() -> {
            authService.sendSmsCode(phone, SmsScene.LOGIN, null);
            String code = code(SmsScene.LOGIN, phone);
            LoginVO stranger = authService.smsLogin(phone, code, null);
            assertFalse(stranger.isDeactivationCancelled(), "到期的号已释放，这次登录建的是一个新游客");
        });
        assertEquals(UserStatus.DELETED, reload(v.getId()).getStatus());
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

    /** 恢复：只收已注销的；手机号被占用要换一个；恢复后账号正常，openid 按 id 合成。 */
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
        assertEquals(VolunteerDeactivationService.RESTORED_OPENID_PREFIX + v.getId(), back.getOpenid(),
                "按 id 合成，不按手机号——手机号合成的那个可能已被别的账号占着");
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

    /**
     * 恢复时填的号，正好是某个账号「用它验证码登录建号、后来换绑走了」的那个号：那个账号的 openid 还是按这个号合成的
     * {@code p:哈希}（换绑不改 openid），而手机号查重看不到它。恢复要照样成功，不能报一句莫名其妙的「手机号已被占用」。
     */
    @Test
    void restore_toANumberWhoseSyntheticOpenidIsHeldByAnotherAccount_succeeds() {
        Volunteer leaver = registered(phone());
        makeDue(leaver.getId());
        assertTrue(deactivationService.finalizeIfDue(leaver.getId()));

        String x = phone();
        Volunteer movedOn = registered(x); // openid = p:hash(x)
        String z = phone();
        jdbc.update("UPDATE volunteer SET phone = ?, phone_hash = ? WHERE id = ?",
                cryptoUtil.encrypt(z), cryptoUtil.hashPhone(z), movedOn.getId());

        deactivationService.restore(leaver.getId(), x, 900L);
        Volunteer back = reload(leaver.getId());
        assertEquals(UserStatus.NORMAL, back.getStatus());
        assertEquals(cryptoUtil.hashPhone(x), back.getPhoneHash());
    }

    /**
     * 冷静期里被禁用：注销照样按时落定（号按时释放）；期间看得到、撤得了；但禁用期间不能<b>新</b>申请。
     * 此前 isDue 只认账号正常，禁用把注销冻住，解禁那一刻又瞬间注销、连撤销的机会都没有。
     */
    @Test
    void bannedDuringCoolingOff_canStillSeeAndCancel_andFinalizesOnTime() {
        Volunteer cancelling = registered(phone());
        jdbc.update("UPDATE volunteer SET deactivate_request_time = NOW(), "
                + "deactivate_effective_time = DATE_ADD(NOW(), INTERVAL 3 DAY), status = 1 WHERE id = ?",
                cancelling.getId());
        assertTrue(deactivationService.status(cancelling.getId()).pending(), "禁用了也看得到");
        deactivationService.cancel(cancelling.getId());
        assertNull(reload(cancelling.getId()).getDeactivateEffectiveTime(), "禁用了也撤得了");
        assertEquals(UserStatus.BANNED, reload(cancelling.getId()).getStatus(), "撤销不动禁用状态");
        assertEquals("没有待生效的注销申请",
                assertThrows(BusinessException.class, () -> deactivationService.cancel(cancelling.getId())).getMessage());
        BusinessException fresh = assertThrows(BusinessException.class,
                () -> deactivationService.request(cancelling.getId(), "123456"));
        assertTrue(fresh.getMessage().contains("禁用"), "禁用期间不能新申请：" + fresh.getMessage());

        String phone = phone();
        Volunteer leaving = registered(phone);
        makeDue(leaving.getId());
        jdbc.update("UPDATE volunteer SET status = 1 WHERE id = ?", leaving.getId());
        assertTrue(deactivationService.finalizeAllDue() >= 1);
        Volunteer gone = reload(leaving.getId());
        assertEquals(UserStatus.DELETED, gone.getStatus(), "禁用中的也按时落定");
        assertNull(gone.getPhoneHash(), "号按时释放");
    }

    /**
     * 同上，但走「碰到即落定」那条路（登录 / 请求闸门都经 {@code finalizeIfDue(Volunteer)} → {@code isDue}），
     * 不经定时收尾——收尾任务直接调按 id 落定的 UPDATE，证明不了 {@code isDue} 认不认禁用账号。
     */
    @Test
    void bannedAndPastDue_isFinalizedTheFirstTimeSomethingTouchesIt() {
        Volunteer byEntity = registered(phone());
        makeDue(byEntity.getId());
        jdbc.update("UPDATE volunteer SET status = 1 WHERE id = ?", byEntity.getId());
        Volunteer loaded = reload(byEntity.getId());
        assertTrue(deactivationService.isDue(loaded), "禁用且已过冷静期＝到期");
        assertTrue(deactivationService.finalizeIfDue(loaded), "请求闸门 / 登录就是这样落定的");
        assertEquals(UserStatus.DELETED, reload(byEntity.getId()).getStatus());

        // 验证码登录：按手机号查到这个禁用的到期账号，当场落定、号释放，陌生号建一个新游客
        String phone = phone();
        Volunteer byLogin = registered(phone);
        makeDue(byLogin.getId());
        jdbc.update("UPDATE volunteer SET status = 1 WHERE id = ?", byLogin.getId());
        authService.sendSmsCode(phone, SmsScene.LOGIN, null);
        String code = code(SmsScene.LOGIN, phone);
        try {
            authService.smsLogin(phone, code, null);
        } catch (Exception ignored) {
            // 非 web 上下文 StpUtil.login 抛错；落定在它之前完成
        }
        Volunteer gone = reload(byLogin.getId());
        assertEquals(UserStatus.DELETED, gone.getStatus(), "禁用中的到期账号被登录碰到即落定");
        assertNull(gone.getPhoneHash(), "号释放");
    }

    /** 被判「拒绝使用本程序」的人：闸门为了让他看得到、撤得了已有的申请而放行了这条路径，但新申请照旧不收。 */
    @Test
    void deniedAllUse_cannotStartANewDeactivation() {
        String phone = phone();
        Volunteer v = registered(phone);
        sanctionService.impose(v.getId(), 1, SEQ.incrementAndGet(), SanctionScope.ALL, 7);
        authService.sendSmsCode(phone, SmsScene.ACCOUNT_DEACTIVATE, null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> deactivationService.request(v.getId(), code(SmsScene.ACCOUNT_DEACTIVATE, phone)));
        assertTrue(ex.getMessage().contains(SanctionScope.labelOf(SanctionScope.ALL)), ex.getMessage());
        assertNull(reload(v.getId()).getDeactivateEffectiveTime());
    }

    /** 落定时让出分队名额（分队人数按 volunteer.squad_id 计），并发出事件让 organization 收拾小组与组织架构。 */
    @Test
    void finalizing_clearsTheSquadSeat() {
        Volunteer v = registered(phone());
        jdbc.update("UPDATE volunteer SET squad_id = 424242 WHERE id = ?", v.getId());
        makeDue(v.getId());
        assertTrue(deactivationService.finalizeIfDue(v.getId()));
        assertNull(reload(v.getId()).getSquadId());
    }

    /**
     * 只读「账号正常不正常」的地方不等落定：已过冷静期、还没被任何入口碰到的账号，一律按已注销算——
     * 不再收通知短信、不能再按手机号被找到（发卷、指派核销员、发证）、不算正常账号。
     */
    @Test
    void pastDueButNotYetFinalized_isAlreadyTreatedAsDeactivatedByReaders() {
        String phone = phone();
        Volunteer v = registered(phone);
        assertTrue(queryService.isActive(v.getId()));
        makeDue(v.getId());
        assertEquals(UserStatus.NORMAL, reload(v.getId()).getStatus(), "前提：库里还没落定");

        assertFalse(queryService.isActive(v.getId()));
        assertTrue(queryService.filterActiveRegistered(List.of(v.getId())).isEmpty());
        assertFalse(queryService.hasVerifiedPhone(v.getId()));
        assertTrue(queryService.listNotifiablePhones(List.of(v.getId())).isEmpty(), "已过冷静期不再发短信");
        assertTrue(queryService.findIdsByPhones(List.of(phone)).isEmpty(), "号已释放，不能再按它找到这个人");
        assertEquals(UserStatus.DELETED, queryService.getProfileForEligibility(v.getId()).status());
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
