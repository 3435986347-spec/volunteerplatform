package com.hengde.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.config.AuthProperties;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.sms.SmsScene;
import com.hengde.common.sms.VerifyCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p><b>冷静期内</b>账号照常可用、本人可随时撤销、手机号不释放（提前释放就撤销不回来了）。</p>
 *
 * <p><b>到没到期按时间现算</b>，不靠定时任务改状态位（与处置到期同一条纪律：漏跑一次就会让人白等，
 * 或让一个本该释放的手机号迟迟注册不了）。到期之后<b>第一次碰到这个账号</b>就当场落定——请求闸门
 * （{@code SaTokenConfigure}）、登录（{@link #finalizeIfDue(Volunteer)} 在 {@code ensureLoginable} 里）、
 * 按手机号查找（{@link #releasePhoneIfDue}：登录建号、注册查重、换绑查重、恢复查重）。
 * {@code DeactivationFinalizeJob} 每 10 分钟把没人碰的也收拾掉，只是收尾、不是正确性的依据。</p>
 *
 * <p><b>落定做什么</b>：status=2、清手机号 / 手机号哈希 / 密码、合成 openid 换成 {@code deactivated:{id}}——
 * 手机号验证码登录建的号 openid 是由手机号哈希合成的，不换的话同一个号重新注册会撞 openid 唯一键；
 * 身份证哈希<b>不动</b>，{@code uk_id_card_hash} 正是「身份证不能再注册」的落点。落定是一条带条件的 UPDATE
 * （仍正常且已到期），多处同时碰到只成一次。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class VolunteerDeactivationService {

    /** 落定后合成 openid 的前缀；恢复账号时据此换回按手机号合成的 openid。 */
    public static final String DEACTIVATED_OPENID_PREFIX = "deactivated:";

    private VolunteerMapper volunteerMapper;
    private VerifyCodeService verifyCodeService;
    private CryptoUtil cryptoUtil;
    private AuthProperties authProperties;

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

    // ================= 志愿者本人 =================

    /** 注销状态：是否在冷静期内、何时申请、何时生效、冷静期天数。 */
    public record Status(boolean pending, LocalDateTime requestTime, LocalDateTime effectiveTime, int coolingDays) {
    }

    public Status status(Long volunteerId) {
        Volunteer v = requireNormal(volunteerId);
        return new Status(v.getDeactivateEffectiveTime() != null, v.getDeactivateRequestTime(),
                v.getDeactivateEffectiveTime(), authProperties.getDeactivationCoolingDays());
    }

    /**
     * 申请注销：验证码发到本人手机号（{@link SmsScene#ACCOUNT_DEACTIVATE}），核验通过后进入冷静期。
     *
     * @return 注销生效时间
     */
    public LocalDateTime request(Long volunteerId, String smsCode) {
        Volunteer v = requireNormal(volunteerId);
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
     * 冷静期内撤销注销。已经到期的撤销不了（那时已经注销了，要回来请联系协会恢复账号）。
     *
     * <p>到期由两道挡：{@link #requireNormal} 先按时间判、到期即落定并报错（顺序调用都在这一道被挡下）；
     * UPDATE 里的 {@code effective_time > now} 只管「判完之后、写之前恰好到期」那不到一毫秒的窗口——
     * 变异验证去掉它时用例照样绿，就是因为第一道已经挡住了顺序调用，别据此把它删掉。</p>
     */
    public void cancel(Long volunteerId) {
        Volunteer v = requireNormal(volunteerId);
        if (v.getDeactivateEffectiveTime() == null) {
            throw new BusinessException("没有待生效的注销申请");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = volunteerMapper.update(null, Wrappers.<Volunteer>lambdaUpdate()
                .eq(Volunteer::getId, volunteerId)
                .eq(Volunteer::getStatus, UserStatus.NORMAL)
                .gt(Volunteer::getDeactivateEffectiveTime, now)
                .set(Volunteer::getDeactivateRequestTime, null)
                .set(Volunteer::getDeactivateEffectiveTime, null)
                .set(Volunteer::getUpdateTime, now));
        if (rows != 1) {
            finalizeIfDue(volunteerId);
            throw new BusinessException("冷静期已过，账号已注销；如需重新加入请联系协会恢复账号");
        }
    }

    // ================= 到期落定 =================

    /** 这个账号是不是已经过了冷静期、还没落定。 */
    public boolean isDue(Volunteer v) {
        return v != null && UserStatus.NORMAL.equals(v.getStatus()) && v.getDeactivateEffectiveTime() != null
                && !LocalDateTime.now().isBefore(v.getDeactivateEffectiveTime());
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

    /** 按 id 落定（条件 UPDATE：仍正常且已到期；多处同时碰到只成一次）。 */
    public boolean finalizeIfDue(Long volunteerId) {
        LocalDateTime now = LocalDateTime.now();
        int rows = volunteerMapper.update(null, Wrappers.<Volunteer>lambdaUpdate()
                .eq(Volunteer::getId, volunteerId)
                .eq(Volunteer::getStatus, UserStatus.NORMAL)
                .isNotNull(Volunteer::getDeactivateEffectiveTime)
                .le(Volunteer::getDeactivateEffectiveTime, now)
                .set(Volunteer::getStatus, UserStatus.DELETED)
                .set(Volunteer::getPhone, null)
                .set(Volunteer::getPhoneHash, null)
                .set(Volunteer::getPassword, null)
                // unionid 也清掉：将来服务号模板消息按 UnionID 找人，注销了就不该再被找到（协会：之后发什么都是打扰）
                .set(Volunteer::getUnionid, null)
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
        }
        return rows == 1;
    }

    /**
     * 这个手机号的持有者如果已过冷静期，先把号释放出来——注册、登录建号、换绑、恢复账号查重之前都要先调一次，
     * 否则「协会说手机号释放」要等到有人碰巧碰到那个账号才兑现。
     */
    public void releasePhoneIfDue(String phoneHash) {
        if (!StringUtils.hasText(phoneHash)) {
            return;
        }
        List<Volunteer> holders = volunteerMapper.selectList(Wrappers.<Volunteer>lambdaQuery()
                .eq(Volunteer::getPhoneHash, phoneHash));
        for (Volunteer h : holders) {
            finalizeIfDue(h);
        }
    }

    /** 定时收尾：把已过冷静期、没人碰到的账号落定。返回落定条数。 */
    public int finalizeAllDue() {
        List<Volunteer> due = volunteerMapper.selectList(Wrappers.<Volunteer>lambdaQuery()
                .select(Volunteer::getId)
                .eq(Volunteer::getStatus, UserStatus.NORMAL)
                .isNotNull(Volunteer::getDeactivateEffectiveTime)
                .le(Volunteer::getDeactivateEffectiveTime, LocalDateTime.now())
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
            // 与手机号验证码登录建号同一种合成方式："p:" + 62 位哈希，满足 NOT NULL UNIQUE 且 ≤ VARCHAR(64)
            uw.set(Volunteer::getOpenid, "p:" + phoneHash.substring(0, 62));
        }
        try {
            if (volunteerMapper.update(null, uw) != 1) {
                throw new BusinessException("账号状态已变化，请刷新后再试");
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException("这个手机号已被其他账号使用，请换一个");
        }
        log.info("[DEACTIVATE] 恢复账号 volunteerId={} by admin={}", volunteerId, adminId);
    }

    private Volunteer requireNormal(Long volunteerId) {
        Volunteer v = volunteerId == null ? null : volunteerMapper.selectById(volunteerId);
        if (v == null) {
            throw new BusinessException("登录态异常，请重新登录");
        }
        if (finalizeIfDue(v)) {
            throw new BusinessException("冷静期已过，账号已注销；如需重新加入请联系协会恢复账号");
        }
        if (!UserStatus.NORMAL.equals(v.getStatus())) {
            throw new BusinessException("账号状态异常，无法操作");
        }
        return v;
    }

    private String decrypt(String cipher) {
        return StringUtils.hasText(cipher) ? cryptoUtil.decrypt(cipher) : null;
    }
}
