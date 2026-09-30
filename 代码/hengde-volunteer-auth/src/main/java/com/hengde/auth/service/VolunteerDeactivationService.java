package com.hengde.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.config.AuthProperties;
import com.hengde.auth.constant.SanctionScope;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.event.VolunteerDeactivatedEvent;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.sms.SmsScene;
import com.hengde.common.sms.VerifyCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 志愿者注销（7 天冷静期）与后台恢复账号。
 *
 * <p><b>依据</b>：协会 2026-09-16——注销＝<b>退出协会</b>，之后发什么都是打扰；数据不删只为避免系统紊乱；
 * 能做的话建议加 <b>7 天冷静期</b>；注销后<b>手机号释放</b>（别人可以用这个号注册）、<b>身份证不能再注册</b>；
 * 本人想回来时由<b>理事会</b>在后台恢复账号。</p>
 *
 * <p><b>冷静期内</b>账号照常可用、本人可随时撤销、手机号不释放（提前释放就撤销不回来了）。
 * 冷静期里被禁用的账号<b>照样按时落定、照样能查看与撤销</b>——注销是他自己在禁用之前做的决定，
 * 禁用不该把它冻住（冻住的话手机号迟迟不释放，解禁那一刻又瞬间注销、连撤销的机会都没有）。</p>
 *
 * <p><b>到没到期按时间现算</b>，不靠定时任务改状态位（与处置到期同一条纪律）。两层保证：
 * ① 到期之后<b>第一次碰到这个账号</b>就当场落定——请求闸门（{@code SaTokenConfigure}）、
 * 登录（{@link #finalizeIfDue(Volunteer)} 在 {@code ensureLoginable} 里）、按手机号查找与查重
 * （{@link #releasePhoneIfDue}）；② 其余只读「账号正常不正常」的地方（{@code VolunteerQueryService}
 * 里的资格、通知收件人、按手机号换 id 等）用 {@link #isPastDue} 把「已过冷静期」当作已注销，
 * 不等它被落定。{@code DeactivationFinalizeJob} 每 10 分钟把没人碰的也收拾掉，只是让库里的状态与实际一致。</p>
 *
 * <p><b>落定做什么</b>：status=2、清手机号 / 手机号哈希 / 密码 / unionid / 归属分队、合成 openid 换成
 * {@code deactivated:{id}}——手机号验证码登录建的号 openid 是由手机号哈希合成的，不换的话同一个号重新注册会撞
 * openid 唯一键；身份证哈希<b>不动</b>，{@code uk_id_card_hash} 正是「身份证不能再注册」的落点。
 * 小组、组织架构里的位置由 organization 订阅 {@link VolunteerDeactivatedEvent} 让出来。
 * 落定是一条带条件的 UPDATE（仍未注销且已到期），多处同时碰到只成一次。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class VolunteerDeactivationService {

    /** 落定后合成 openid 的前缀。 */
    public static final String DEACTIVATED_OPENID_PREFIX = "deactivated:";

    /**
     * 恢复账号后合成 openid 的前缀。<b>按 id 合成、不按手机号</b>：手机号合成的 {@code p:哈希} 可能已经被一个
     * 「用这个号验证码登录、之后又换绑走了」的账号占着（它换绑之后 openid 不变），按手机号合成就会撞 openid 唯一键，
     * 而管理员看到的只会是「手机号已被占用」。验证码登录按手机号哈希找人、不看 openid，用 id 合成不影响登录。
     */
    public static final String RESTORED_OPENID_PREFIX = "restored:";

    private VolunteerMapper volunteerMapper;
    private VerifyCodeService verifyCodeService;
    private CryptoUtil cryptoUtil;
    private AuthProperties authProperties;
    private SanctionQueryService sanctionQueryService;
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    @Autowired
    public void setVerifyCodeService(VerifyCodeService verifyCodeService) {
        this.verifyCodeService = verifyCodeService;
    }

    @Autowired
    public void setCryptoUtil(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    @Autowired
    public void setAuthProperties(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Autowired
    public void setSanctionQueryService(SanctionQueryService sanctionQueryService) {
        this.sanctionQueryService = sanctionQueryService;
    }

    @Autowired
    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    // ================= 志愿者本人 =================

    /** 注销状态：是否在冷静期内、何时申请、何时生效、冷静期天数。 */
    public record Status(boolean pending, LocalDateTime requestTime, LocalDateTime effectiveTime, int coolingDays) {
    }

    /** 查看注销状态。账号正常或被禁用都能看（禁用之前申请过的，要让他看得到、撤得了）。 */
    public Status status(Long volunteerId) {
        Volunteer v = requireLive(volunteerId);
        return new Status(v.getDeactivateEffectiveTime() != null, v.getDeactivateRequestTime(),
                v.getDeactivateEffectiveTime(), authProperties.getDeactivationCoolingDays());
    }

    /**
     * 申请注销：验证码发到本人手机号（{@link SmsScene#ACCOUNT_DEACTIVATE}），核验通过后进入冷静期。
     *
     * <p><b>新申请只收账号正常、且没被判「拒绝使用本程序」的人</b>——这两种人此前在闸门上就申请不了，
     * 放行这条路径是为了让他们看得到、撤得了<b>此前</b>的申请，不是为了多开一个口子。</p>
     *
     * <p>⚠️ 验证码只能证明「此刻绑定的手机号在他手上」。换绑手机号因此必须同时验证原手机号
     * （{@code MyProfileService.changePhone}），否则拿到登录态的人先换绑成自己的号、再来这里注销，
     * 这道验证就形同虚设。</p>
     *
     * @return 注销生效时间
     */
    public LocalDateTime request(Long volunteerId, String smsCode) {
        Volunteer v = requireLive(volunteerId);
        if (!UserStatus.NORMAL.equals(v.getStatus())) {
            throw new BusinessException("账号已被禁用，暂不能申请注销；如有疑问请联系协会");
        }
        if (sanctionQueryService.isRestricted(volunteerId, SanctionScope.ALL)) {
            throw new BusinessException("您当前被" + SanctionScope.labelOf(SanctionScope.ALL) + "，暂不能申请注销");
        }
        if (v.getDeactivateEffectiveTime() != null) {
            throw new BusinessException("已经申请过注销，冷静期至 " + v.getDeactivateEffectiveTime().toLocalDate()
                    + "，期内可以撤销");
        }
        String phone = decrypt(v.getPhone());
        if (!StringUtils.hasText(phone)) {
            throw new BusinessException("账号没有绑定手机号，无法验证身份，请先绑定手机号");
        }
        if (!StringUtils.hasText(smsCode)) {
            throw new BusinessException("请填写短信验证码");
        }
        verifyCodeService.verify(phone, SmsScene.ACCOUNT_DEACTIVATE, smsCode.trim());
        // 取整到秒：DATETIME 写入是四舍五入，出参与之后读库会差一秒（处置批、志愿者证批都撞过）
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime effective = now.plusDays(authProperties.getDeactivationCoolingDays());
        int rows = volunteerMapper.update(null, Wrappers.<Volunteer>lambdaUpdate()
                .eq(Volunteer::getId, volunteerId)
                .eq(Volunteer::getStatus, UserStatus.NORMAL)
                .isNull(Volunteer::getDeactivateEffectiveTime)
                .set(Volunteer::getDeactivateRequestTime, now)
                .set(Volunteer::getDeactivateEffectiveTime, effective)
                .set(Volunteer::getUpdateTime, now));
        if (rows != 1) {
            throw new BusinessException("已经申请过注销，或账号状态已变化，请刷新后再试");
        }
        return effective;
    }

    /**
     * 冷静期内撤销注销（账号正常或被禁用都能撤）。已经到期的撤销不了（那时已经注销了，要回来请联系协会恢复账号）。
     *
     * <p>到期由两道挡：{@link #requireLive} 先按时间判、到期即落定并报错（顺序调用都在这一道被挡下）；
     * UPDATE 里的 {@code effective_time > now} 只管「判完之后、写之前恰好到期」那不到一毫秒的窗口——
     * 变异验证去掉它时用例照样绿，就是因为第一道已经挡住了顺序调用，别据此把它删掉。</p>
     *
     * <p>UPDATE 没改到行时<b>重新读一次</b>再说原因：可能是刚好到期（已落定），也可能是别处已经撤销、
     * 或账号刚被注销——一律报「冷静期已过」会把后两种说错。</p>
     */
    public void cancel(Long volunteerId) {
        Volunteer v = requireLive(volunteerId);
        if (v.getDeactivateEffectiveTime() == null) {
            throw new BusinessException("没有待生效的注销申请");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = volunteerMapper.update(null, Wrappers.<Volunteer>lambdaUpdate()
                .eq(Volunteer::getId, volunteerId)
                .in(Volunteer::getStatus, UserStatus.NORMAL, UserStatus.BANNED)
                .gt(Volunteer::getDeactivateEffectiveTime, now)
                .set(Volunteer::getDeactivateRequestTime, null)
                .set(Volunteer::getDeactivateEffectiveTime, null)
                .set(Volunteer::getUpdateTime, now));
        if (rows == 1) {
            return;
        }
        Volunteer latest = volunteerMapper.selectById(volunteerId);
        if (latest != null && (finalizeIfDue(latest) || UserStatus.DELETED.equals(latest.getStatus()))) {
            throw new BusinessException("冷静期已过，账号已注销；如需重新加入请联系协会恢复账号");
        }
        if (latest != null && latest.getDeactivateEffectiveTime() == null) {
            throw new BusinessException("注销申请已经撤销了");
        }
        throw new BusinessException("账号状态已变化，请刷新后再试");
    }

    // ================= 到期落定 =================

    /**
     * 这一行是不是「已过冷静期」：申请过注销、且生效时间已到。<b>不看 status</b>——
     * 只读账号状态的地方拿它和 status 一起判「现在算不算已注销」（见 {@code VolunteerQueryService}）。
     */
    public static boolean isPastDue(Volunteer v, LocalDateTime now) {
        return v != null && v.getDeactivateEffectiveTime() != null && !now.isBefore(v.getDeactivateEffectiveTime());
    }

    /** 这个账号是不是已经过了冷静期、还没落定（正常或禁用；已注销的不算）。 */
    public boolean isDue(Volunteer v) {
        return v != null && isLiveStatus(v.getStatus()) && isPastDue(v, LocalDateTime.now());
    }

    /**
     * 已过冷静期就当场落定。
     *
     * @return true = 这个账号现在是已注销（刚落定的，或 {@code v} 传进来时就是到期的）
     */
    public boolean finalizeIfDue(Volunteer v) {
        if (!isDue(v)) {
            return false;
        }
        finalizeIfDue(v.getId());
        return true;
    }

    /** 按 id 落定（条件 UPDATE：仍正常或禁用、且已到期；多处同时碰到只成一次）。 */
    public boolean finalizeIfDue(Long volunteerId) {
        LocalDateTime now = LocalDateTime.now();
        int rows = volunteerMapper.update(null, Wrappers.<Volunteer>lambdaUpdate()
                .eq(Volunteer::getId, volunteerId)
                .in(Volunteer::getStatus, UserStatus.NORMAL, UserStatus.BANNED)
                .isNotNull(Volunteer::getDeactivateEffectiveTime)
                .le(Volunteer::getDeactivateEffectiveTime, now)
                .set(Volunteer::getStatus, UserStatus.DELETED)
                .set(Volunteer::getPhone, null)
                .set(Volunteer::getPhoneHash, null)
                .set(Volunteer::getPassword, null)
                // unionid 也清掉：将来服务号模板消息按 UnionID 找人，注销了就不该再被找到（协会：之后发什么都是打扰）
                .set(Volunteer::getUnionid, null)
                // 分队名额按 volunteer.squad_id 计数：不清的话退出协会的人会永久占着分队的人数上限
                .set(Volunteer::getSquadId, null)
                .setSql("openid = CONCAT('" + DEACTIVATED_OPENID_PREFIX + "', id)")
                .set(Volunteer::getDeactivatedTime, now)
                .set(Volunteer::getUpdateTime, now));
        if (rows == 1) {
            log.info("[DEACTIVATE] 注销落定 volunteerId={}", volunteerId);
            try {
                StpUtil.logout(volunteerId);
            } catch (RuntimeException e) {
                // 登录态在下一次请求时由闸门兜底（status 已是注销），这里失败不影响落定
                log.warn("[DEACTIVATE] 注销落定后踢下线失败 volunteerId={}：{}", volunteerId, e.getMessage());
            }
            // 在调用方事务里时，订阅方（organization）按 AFTER_COMMIT 在提交后才收拾；回滚则什么都不发生
            eventPublisher.publishEvent(new VolunteerDeactivatedEvent(volunteerId));
        }
        return rows == 1;
    }

    /**
     * 这个手机号的持有者如果已过冷静期，先把号释放出来——注册、换绑、恢复账号查重之前都要先调一次，
     * 否则「协会说手机号释放」要等到有人碰巧碰到那个账号才兑现。
     *
     * <p>只查「这个号上已到期的」那几列（走 {@code uk_phone_hash}），平常一行都查不到。</p>
     */
    public void releasePhoneIfDue(String phoneHash) {
        if (!StringUtils.hasText(phoneHash)) {
            return;
        }
        List<Volunteer> holders = volunteerMapper.selectList(Wrappers.<Volunteer>lambdaQuery()
                .select(Volunteer::getId, Volunteer::getStatus, Volunteer::getDeactivateEffectiveTime)
                .eq(Volunteer::getPhoneHash, phoneHash)
                .isNotNull(Volunteer::getDeactivateEffectiveTime)
                .le(Volunteer::getDeactivateEffectiveTime, LocalDateTime.now()));
        for (Volunteer h : holders) {
            finalizeIfDue(h);
        }
    }

    /** 定时收尾：把已过冷静期、没人碰到的账号落定。返回落定条数。 */
    public int finalizeAllDue() {
        List<Volunteer> due = volunteerMapper.selectList(Wrappers.<Volunteer>lambdaQuery()
                .select(Volunteer::getId)
                .isNotNull(Volunteer::getDeactivateEffectiveTime)
                .le(Volunteer::getDeactivateEffectiveTime, LocalDateTime.now())
                .in(Volunteer::getStatus, UserStatus.NORMAL, UserStatus.BANNED)
                .last("LIMIT 500"));
        int n = 0;
        for (Volunteer v : due) {
            n += finalizeIfDue(v.getId()) ? 1 : 0;
        }
        return n;
    }

    // ================= 后台恢复账号（理事会） =================

    /**
     * 恢复已注销的账号：换一个<b>当前没被占用</b>的手机号（协会 2026-09-16「恢复时换成真实号码」；
     * 号释放之后原号可能已被别人注册，所以由理事会填一个号——《待协会答复》乙-3 默认 (a)）。
     * 恢复后账号正常、没有密码，本人用这个号收验证码登录即可。
     *
     * <p>注销时让出去的分队 / 小组 / 组织架构位置<b>不会</b>跟着回来——那些要按原流程重新申请或由管理员放回。</p>
     */
    public void restore(Long volunteerId, String newPhone, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        Volunteer v = volunteerId == null ? null : volunteerMapper.selectById(volunteerId);
        if (v != null) {
            finalizeIfDue(v);
            v = volunteerMapper.selectById(volunteerId);
        }
        if (v == null || !UserStatus.DELETED.equals(v.getStatus())) {
            throw new BusinessException("只有已注销的账号可以恢复");
        }
        String phone = newPhone == null ? "" : newPhone.trim();
        if (!phone.matches("^1[3-9]\\d{9}$")) {
            throw new BusinessException("请填写正确的手机号");
        }
        String phoneHash = cryptoUtil.hashPhone(phone);
        releasePhoneIfDue(phoneHash);
        Long taken = volunteerMapper.selectCount(Wrappers.<Volunteer>lambdaQuery()
                .eq(Volunteer::getPhoneHash, phoneHash)
                .ne(Volunteer::getId, volunteerId));
        if (taken != null && taken > 0) {
            throw new BusinessException("这个手机号已被其他账号使用，请换一个");
        }
        LocalDateTime now = LocalDateTime.now();
        var uw = Wrappers.<Volunteer>lambdaUpdate()
                .eq(Volunteer::getId, volunteerId)
                .eq(Volunteer::getStatus, UserStatus.DELETED)
                .set(Volunteer::getStatus, UserStatus.NORMAL)
                .set(Volunteer::getPhone, cryptoUtil.encrypt(phone))
                .set(Volunteer::getPhoneHash, phoneHash)
                .set(Volunteer::getDeactivateRequestTime, null)
                .set(Volunteer::getDeactivateEffectiveTime, null)
                .set(Volunteer::getRestoreBy, adminId)
                .set(Volunteer::getRestoreTime, now)
                .set(Volunteer::getUpdateTime, now);
        if (v.getOpenid() != null && v.getOpenid().startsWith(DEACTIVATED_OPENID_PREFIX)) {
            uw.set(Volunteer::getOpenid, RESTORED_OPENID_PREFIX + volunteerId);
        }
        try {
            if (volunteerMapper.update(null, uw) != 1) {
                throw new BusinessException("账号状态已变化，请刷新后再试");
            }
        } catch (DuplicateKeyException e) {
            // openid 按 id 合成不会撞，唯一可能撞的是 uk_phone_hash（查重与写入之间被人抢注）
            throw new BusinessException("这个手机号已被其他账号使用，请换一个");
        }
        log.info("[DEACTIVATE] 恢复账号 volunteerId={} by admin={}", volunteerId, adminId);
    }

    /** 正常或禁用，且没过冷静期；过了冷静期当场落定并报错。 */
    private Volunteer requireLive(Long volunteerId) {
        Volunteer v = volunteerId == null ? null : volunteerMapper.selectById(volunteerId);
        if (v == null) {
            throw new BusinessException("登录态异常，请重新登录");
        }
        if (finalizeIfDue(v)) {
            throw new BusinessException("冷静期已过，账号已注销；如需重新加入请联系协会恢复账号");
        }
        if (!isLiveStatus(v.getStatus())) {
            throw new BusinessException("账号状态异常，无法操作");
        }
        return v;
    }

    private static boolean isLiveStatus(Integer status) {
        return UserStatus.NORMAL.equals(status) || UserStatus.BANNED.equals(status);
    }

    private String decrypt(String cipher) {
        return StringUtils.hasText(cipher) ? cryptoUtil.decrypt(cipher) : null;
    }
}
