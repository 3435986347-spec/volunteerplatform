package com.hengde.auth.service;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdcardUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.config.AuthProperties;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.dto.RegisterDTO;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.integration.RealNameService;
import com.hengde.auth.integration.WeworkGroupService;
import com.hengde.auth.vo.AgreementVO;
import com.hengde.auth.vo.LoginVO;
import com.hengde.common.constant.Gender;
import com.hengde.common.constant.Grade;
import com.hengde.common.constant.PoliticalStatus;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.sms.SmsScene;
import com.hengde.common.sms.VerifyCodeService;
import com.hengde.common.utils.PasswordUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * 志愿者端认证服务：微信登录、发注册验证码、实名注册、企业微信群校验、退出。
 *
 * <p>登录态走默认 {@code StpUtil}（loginType=login），loginId 为 {@code volunteer.id}。
 * 微信登录即建行（游客态），实名注册后置 {@code registerTime}。依赖按约定 setter 注入。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class VolunteerAuthService {

    private static final DateTimeFormatter BIRTH_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 发码允许的场景白名单（仅志愿者域，防被借道发任意场景码） */
    private static final Set<String> ALLOWED_SMS_SCENES =
            Set.of(SmsScene.REGISTER, SmsScene.LOGIN, SmsScene.VOLUNTEER_PASSWORD_RESET, SmsScene.CHANGE_PHONE);

    private VolunteerMapper volunteerMapper;
    private WxMaService wxMaService;
    private VerifyCodeService verifyCodeService;
    private CryptoUtil cryptoUtil;
    private RealNameService realNameService;
    private WeworkGroupService weworkGroupService;
    private AuthProperties authProperties;
    private LoginProtectionService loginProtectionService;

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    @Autowired
    public void setAuthProperties(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Autowired
    public void setWxMaService(WxMaService wxMaService) {
        this.wxMaService = wxMaService;
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
    public void setRealNameService(RealNameService realNameService) {
        this.realNameService = realNameService;
    }

    @Autowired
    public void setWeworkGroupService(WeworkGroupService weworkGroupService) {
        this.weworkGroupService = weworkGroupService;
    }

    @Autowired
    public void setLoginProtectionService(LoginProtectionService loginProtectionService) {
        this.loginProtectionService = loginProtectionService;
    }

    /**
     * 微信小程序登录：code 换 openid，首次登录建游客行，返回 token 与是否已实名。
     */
    public LoginVO wechatLogin(String code) {
        String openid;
        String unionid;
        try {
            WxMaJscode2SessionResult session = wxMaService.getUserService().getSessionInfo(code);
            openid = session.getOpenid();
            unionid = session.getUnionid();
        } catch (Exception e) {
            log.error("[Auth] 微信 jscode2session 失败", e);
            throw new BusinessException("微信登录失败，请重试");
        }

        Volunteer volunteer = volunteerMapper.selectOne(
                Wrappers.<Volunteer>lambdaQuery().eq(Volunteer::getOpenid, openid));
        if (volunteer == null) {
            volunteer = new Volunteer();
            volunteer.setOpenid(openid);
            volunteer.setUnionid(unionid);
            volunteer.setStatus(UserStatus.NORMAL);
            volunteerMapper.insert(volunteer);
        } else {
            // 收口到 ensureLoginable 一处：此前这里只挡 BANNED，而 smsLogin/passwordLogin 走的是
            // 「只放 NORMAL」。三条登录路径各写各的判定，改口径时必然漏掉其中一条——
            // 协会这次要求禁用可登录，正好把三份判定并成一份。
            // 【顺带修掉一处不一致】此前微信登录不挡已注销账号，自己注销过的人照样能登回来。
            ensureLoginable(volunteer);
        }

        StpUtil.login(volunteer.getId());
        return new LoginVO(StpUtil.getTokenValue(), volunteer.getRegisterTime() != null);
    }

    /**
     * 开发登录：跳过微信 code 换 openid，按测试 key 找/建一个志愿者并发 token，供前端在无小程序
     * appid/secret 时联调。<b>仅 {@code hengde.auth.dev-login-enabled=true} 时可用</b>（生产由
     * {@code ProductionConfigGuard} 强制为 false）。
     *
     * @param key        测试身份标识（openid 落为 {@code dev:{key}}，留空默认 tester）
     * @param registered true 则直接造成「已实名志愿者」（跳过注册流程，便于测试实名后功能）
     */
    public LoginVO devLogin(String key, boolean registered) {
        if (!authProperties.isDevLoginEnabled()) {
            throw new BusinessException("开发登录未启用");
        }
        String safeKey = StringUtils.hasText(key) ? key.trim() : "tester";
        String openid = "dev:" + safeKey;

        Volunteer volunteer = volunteerMapper.selectOne(
                Wrappers.<Volunteer>lambdaQuery().eq(Volunteer::getOpenid, openid));
        if (volunteer == null) {
            volunteer = new Volunteer();
            volunteer.setOpenid(openid);
            volunteer.setStatus(UserStatus.NORMAL);
            volunteerMapper.insert(volunteer);
        } else {
            ensureLoginable(volunteer);
        }

        // 造一个可用的「已实名」测试身份：填姓名 + 成年生日 + 性别，避免年龄/性别资格校验取到 null
        if (registered && volunteer.getRegisterTime() == null) {
            // real_name 是 VARCHAR(32)：key 长一点（压测里常带随机后缀）就会撞列宽成 500，截到列宽以内
            String devName = "测试志愿者-" + safeKey;
            volunteer.setRealName(devName.length() > 32 ? devName.substring(0, 32) : devName);
            volunteer.setBirthday(LocalDate.of(2000, 1, 1));
            volunteer.setGender(Gender.MALE);
            volunteer.setRegisterTime(LocalDateTime.now());
            volunteerMapper.updateById(volunteer);
        }

        StpUtil.login(volunteer.getId());
        return new LoginVO(StpUtil.getTokenValue(), volunteer.getRegisterTime() != null);
    }

    /**
     * 获取志愿者协议（注册前阅读）：正文 + 版本号，均取自配置。
     */
    public AgreementVO getAgreement() {
        return new AgreementVO(authProperties.getAgreementVersion(), authProperties.getAgreementText());
    }

    /**
     * 发送短信验证码（按场景）。scene 仅允许 register/login/volunteer-password-reset/change-phone（白名单外拒绝）；
     * 留空默认 register（兼容旧调用）。复用 {@link VerifyCodeService} 的手机号/IP 限流 + 错满作废。
     *
     * @param phone    手机号
     * @param scene    场景（见 {@link SmsScene}）
     * @param clientIp 来源 IP（用于发送限流，可空；接口公开，限流是该入口唯一的滥用闸门）
     */
    public void sendSmsCode(String phone, String scene, String clientIp) {
        String s = StringUtils.hasText(scene) ? scene : SmsScene.REGISTER;
        if (!ALLOWED_SMS_SCENES.contains(s)) {
            throw new BusinessException("不支持的验证码场景");
        }
        verifyCodeService.sendCode(phone, s, clientIp);
    }

    /**
     * 手机号 + 验证码登录。校验 LOGIN 场景验证码后按 phoneHash 定位志愿者；
     * <b>陌生手机号自动建游客账号</b>（以手机号为标识，registerTime=null，之后再走实名注册）。
     * 禁用/注销账号拒发 token。
     *
     * @param phone    手机号
     * @param smsCode  短信验证码
     * @param clientIp 来源 IP（预留，当前仅发码侧限流）
     */
    public LoginVO smsLogin(String phone, String smsCode, String clientIp) {
        verifyCodeService.verify(phone, SmsScene.LOGIN, smsCode);
        String phoneHash = cryptoUtil.hashPhone(phone);
        Volunteer volunteer = findOrCreateByPhone(phone, phoneHash);
        ensureLoginable(volunteer);
        StpUtil.login(volunteer.getId());
        return new LoginVO(StpUtil.getTokenValue(), volunteer.getRegisterTime() != null);
    }

    /**
     * 登录可用性校验：<b>正常与禁用都发 token，只有注销拒绝</b>。
     *
     * <p><b>需求出处</b>：协会 2026-08-11 答复第 5 条「账号类处罚：由理事会审核后才生效，
     * <b>只给禁用账号开个小口子、只能看奖惩和提申诉</b>」。</p>
     *
     * <p><b>🔁 「禁用一律拒发 token」这条口径已被推翻</b>。它原本是最直觉的写法，
     * 代价却在别处：禁用账号连登录都进不来，于是一张处罚单审核通过之后，
     * <b>处罚立即生效、而 7 天申诉期在他够不到的地方流逝</b>；禁用超过 7 天，
     * 申诉权就在他不知情的状态下过期了。第 6 轮评审当时的对策是「禁用期间一律不批」
     * （见 {@code RewardPunishService.requireApprovableVolunteer}），
     * 协会选了另一条路：照常批，但把申诉这条路留着。
     * <b>那条对策的理由并没有被推翻</b>——要消除的都是「申诉期在够不到的地方流逝」，
     * 只是手段从「不批」换成了「开口子」。</p>
     *
     * <p><b>「登录成功」从此不再意味着「账号可用」</b>：禁用账号拿到 token 之后，
     * 除 {@code BannedAccountGate.EXEMPT_PATHS} 那几条外，其余 {@code /v/**} 一律 403。
     * 小程序侧应据此把禁用态引导到奖惩记录与申诉页（属后续增量 UX，不阻塞本改动：
     * 改动前是登录那一步就报错，改动后是登录进去大部分接口报 403，
     * 既有的错误提示照常弹出，只是文案不够贴切）。</p>
     *
     * <p><b>注销仍然拒绝</b>：账号是用户自己注销的，没有申诉可提，也没有处罚等着他看。</p>
     *
     * <p>被后台「删除」的账号是逻辑删除 + 唯一字段已释放，{@code selectByPhoneHash} 查不到，
     * 不会走到这里。</p>
     */
    private void ensureLoginable(Volunteer volunteer) {
        Integer status = volunteer.getStatus();
        if (UserStatus.NORMAL.equals(status) || UserStatus.BANNED.equals(status)) {
            return;
        }
        // 【写成白名单而不是「只拦 DELETED」】后者在 status 为 null 或将来多一个取值时会静默放行，
        // 而这是发 token 的那一步——放错了没有第二道防线。
        // 与 SaTokenConfigure.checkVolunteerEnabled 的兜底同一形状。
        throw new BusinessException(UserStatus.DELETED.equals(status) ? "账号已注销" : "账号状态异常");
    }

    /**
     * 按 phoneHash 找<b>活跃</b>志愿者（{@code @TableLogic} 自动过滤已删除行）；没有则建一个游客账号。
     * 并发冷启动（同号两个请求同时建号）由 DB 唯一索引 {@code uk_phone_hash} 兜底——
     * 撞 {@link DuplicateKeyException} 即回查复用，避免重复账号（不引分布式锁，auth 无 Redisson）。
     */
    private Volunteer findOrCreateByPhone(String phone, String phoneHash) {
        Volunteer existing = selectByPhoneHash(phoneHash);
        if (existing != null) {
            return existing;
        }
        Volunteer v = new Volunteer();
        v.setPhone(cryptoUtil.encrypt(phone));
        v.setPhoneHash(phoneHash);
        // 合成 openid 满足 NOT NULL UNIQUE 且 ≤ VARCHAR(64)："p:" + 62 位哈希 = 64；同号确定性、幂等
        v.setOpenid("p:" + phoneHash.substring(0, 62));
        v.setStatus(UserStatus.NORMAL);
        try {
            volunteerMapper.insert(v);
            return v;
        } catch (DuplicateKeyException e) {
            // 并发建号 → 回查复用；若仍查不到 active（残留的旧逻辑删除占位未释放唯一字段等），
            // 转明确业务错误而非把 DuplicateKeyException 抛成 500
            Volunteer raced = selectByPhoneHash(phoneHash);
            if (raced != null) {
                return raced;
            }
            throw new BusinessException("该手机号暂不可用，请联系管理员");
        }
    }

    private Volunteer selectByPhoneHash(String phoneHash) {
        List<Volunteer> matches = volunteerMapper.selectList(
                Wrappers.<Volunteer>lambdaQuery().eq(Volunteer::getPhoneHash, phoneHash));
        if (matches.size() > 1) {
            throw new BusinessException("该手机号对应多个账号，请联系管理员");
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * 手机号 + 密码登录。接防爆破（{@link LoginProtectionService}，按 phoneHash/IP 计数）；
     * 账号不存在/未设密码/密码错误统一报「手机号或密码错误」不泄露存在性；禁用/注销拒登。
     *
     * @param phone    手机号（即账号）
     * @param password 密码明文
     * @param clientIp 来源 IP（防爆破 IP 维度，可空）
     */
    public LoginVO passwordLogin(String phone, String password, String clientIp) {
        String phoneHash = cryptoUtil.hashPhone(phone);
        loginProtectionService.checkVolunteerNotLocked(phoneHash, clientIp);
        Volunteer volunteer = selectByPhoneHash(phoneHash);
        if (volunteer == null || !StringUtils.hasText(volunteer.getPassword())
                || !PasswordUtil.matches(password, volunteer.getPassword())) {
            loginProtectionService.onVolunteerLoginFailed(phoneHash, clientIp);
            throw new BusinessException("手机号或密码错误");
        }
        ensureLoginable(volunteer);
        loginProtectionService.onVolunteerLoginSucceeded(phoneHash);
        StpUtil.login(volunteer.getId());
        return new LoginVO(StpUtil.getTokenValue(), volunteer.getRegisterTime() != null);
    }

    /**
     * 设置/修改登录密码（需登录态，loginId=当前志愿者）。
     * 账号须已绑定手机号（密码登录靠手机号定位）；已有密码时必须校验原密码，首次直接设。
     */
    public void setOrChangePassword(long volunteerId, String oldPassword, String newPassword) {
        Volunteer volunteer = volunteerMapper.selectById(volunteerId);
        if (volunteer == null) {
            throw new BusinessException("登录态异常，请重新登录");
        }
        if (!StringUtils.hasText(volunteer.getPhoneHash())) {
            throw new BusinessException("请先用手机号验证码登录后再设置密码");
        }
        if (StringUtils.hasText(volunteer.getPassword())
                && (!StringUtils.hasText(oldPassword) || !PasswordUtil.matches(oldPassword, volunteer.getPassword()))) {
            throw new BusinessException("原密码错误");
        }
        volunteer.setPassword(PasswordUtil.encrypt(newPassword));
        volunteerMapper.updateById(volunteer);
    }

    /**
     * 忘记密码：手机号 + 验证码（VOLUNTEER_PASSWORD_RESET 场景）重置登录密码。
     */
    public void resetPasswordBySms(String phone, String smsCode, String newPassword) {
        verifyCodeService.verify(phone, SmsScene.VOLUNTEER_PASSWORD_RESET, smsCode);
        String phoneHash = cryptoUtil.hashPhone(phone);
        Volunteer volunteer = selectByPhoneHash(phoneHash);
        if (volunteer == null) {
            throw new BusinessException("该手机号未注册");
        }
        ensureLoginable(volunteer);
        volunteer.setPassword(PasswordUtil.encrypt(newPassword));
        volunteerMapper.updateById(volunteer);
    }

    /**
     * 企业微信群前置校验。
     *
     * @return 已入群返回 true；否则 false（前端据此弹群二维码）
     */
    public boolean checkGroupMembership(String phone) {
        return weworkGroupService.isGroupMember(phone);
    }

    /**
     * 实名注册。要求已微信登录（携带游客 token）。
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginVO register(RegisterDTO dto) {
        long volunteerId = StpUtil.getLoginIdAsLong();
        Volunteer volunteer = volunteerMapper.selectById(volunteerId);
        if (volunteer == null) {
            throw new BusinessException("登录态异常，请重新登录");
        }
        if (volunteer.getRegisterTime() != null) {
            throw new BusinessException("您已完成实名注册，请勿重复提交");
        }
        // 签名图地址长度兜底（DB 列 VARCHAR(512)；防绕过 DTO 校验的内部调用落库截断）
        if (StringUtils.hasText(dto.getSignatureUrl()) && dto.getSignatureUrl().length() > 512) {
            throw new BusinessException("签名图地址过长");
        }

        // 1. 手机号绑定与验证码（方案A：注册手机号锁定 = 登录手机号）。
        //    手机号验证码登录建的账号已绑 phoneHash，且登录时已验证过该号 → 注册手机号须与之一致、
        //    且不再重复发注册验证码；微信登录等未绑手机号的账号仍须校验 REGISTER 验证码后绑定。
        String phoneHash = cryptoUtil.hashPhone(dto.getPhone());
        boolean phoneAlreadyVerified =
                volunteer.getPhoneHash() != null && volunteer.getPhoneHash().equals(phoneHash);
        if (volunteer.getPhoneHash() != null && !volunteer.getPhoneHash().equals(phoneHash)) {
            // 前端已锁定手机号，此处兜底防绕过：不允许把登录态改成别人的号
            throw new BusinessException("登录手机号与注册手机号不一致");
        }
        if (!phoneAlreadyVerified) {
            // DTO 层不做 @NotBlank（已验证手机号合法不传码），未验证场景在此补必填校验
            if (!StringUtils.hasText(dto.getSmsCode())) {
                throw new BusinessException("验证码不能为空");
            }
            verifyCodeService.verify(dto.getPhone(), SmsScene.REGISTER, dto.getSmsCode());
        }

        // 2. 身份证二要素实名
        if (!realNameService.verify(dto.getRealName(), dto.getIdCardNo())) {
            throw new BusinessException("实名认证失败，请核对姓名与身份证号");
        }

        // 3. 企业微信群成员前置
        if (!weworkGroupService.isGroupMember(dto.getPhone())) {
            throw new BusinessException("请先加入企业微信群后再注册");
        }

        // 4. 身份证查重
        String idCardHash = cryptoUtil.hashIdCard(dto.getIdCardNo());
        Long dup = volunteerMapper.selectCount(Wrappers.<Volunteer>lambdaQuery()
                .eq(Volunteer::getIdCardHash, idCardHash)
                .ne(Volunteer::getId, volunteerId));
        if (dup != null && dup > 0) {
            throw new BusinessException("该身份证已注册");
        }

        // 4.5 手机号查重（防串号）：显式查 phoneHash 排除自身，不靠 uk_phone_hash 抛 DB 异常兜底
        Long phoneDup = volunteerMapper.selectCount(Wrappers.<Volunteer>lambdaQuery()
                .eq(Volunteer::getPhoneHash, phoneHash)
                .ne(Volunteer::getId, volunteerId));
        if (phoneDup != null && phoneDup > 0) {
            throw new BusinessException("该手机号已绑定账号，请使用手机号登录");
        }

        // 5. 紧急联系方式：未满 18 岁必填（需求只要紧急联系方式一项，不强制姓名）；填了则不得与本人手机号相同
        int age = IdcardUtil.getAgeByIdCard(dto.getIdCardNo());
        if (age < 18 && !StringUtils.hasText(dto.getEmergencyContactPhone())) {
            throw new BusinessException("未成年志愿者须填写紧急联系方式");
        }
        if (StringUtils.hasText(dto.getEmergencyContactPhone())
                && dto.getPhone().equals(dto.getEmergencyContactPhone())) {
            throw new BusinessException("紧急联系方式不能与本人手机号相同");
        }

        // 6. 填充并加密落库
        volunteer.setRealName(dto.getRealName());
        volunteer.setIdCardNo(cryptoUtil.encrypt(dto.getIdCardNo()));
        volunteer.setIdCardHash(idCardHash);
        volunteer.setPhone(cryptoUtil.encrypt(dto.getPhone()));
        volunteer.setPhoneHash(phoneHash);
        volunteer.setGender(IdcardUtil.getGenderByIdCard(dto.getIdCardNo()) == 1 ? Gender.MALE : Gender.FEMALE);
        volunteer.setBirthday(LocalDate.parse(IdcardUtil.getBirthByIdCard(dto.getIdCardNo()), BIRTH_FMT));
        PoliticalStatus politicalStatus = PoliticalStatus.fromCode(dto.getPoliticalStatus());
        if (politicalStatus == null) {
            throw new BusinessException("政治面貌取值非法");
        }
        Grade grade = Grade.fromCode(dto.getGrade());
        if (dto.getGrade() != null && grade == null) {
            throw new BusinessException("年级取值非法");
        }
        volunteer.setPoliticalStatus(politicalStatus);
        volunteer.setSchool(dto.getSchool());
        volunteer.setGrade(grade);
        if (grade != null) {
            // 注册时填的年级就是当前学年的年级，升级任务从下一个 9 月起才动它（V67）
            volunteer.setGradeUpgradeYear(Grade.schoolYearOf(LocalDate.now()));
        }
        volunteer.setAddress(dto.getAddress());
        volunteer.setIVolunteerCodeUrl(dto.getIVolunteerCodeUrl());
        volunteer.setAvatarUrl(dto.getAvatarUrl());
        volunteer.setEmergencyContactName(dto.getEmergencyContactName());
        volunteer.setEmergencyContactPhone(cryptoUtil.encrypt(dto.getEmergencyContactPhone()));
        volunteer.setSignatureUrl(dto.getSignatureUrl());
        // 入库标记：记录注册时服务端当前的协议版本（手写签名图本身是签署凭据）
        volunteer.setSignedAgreementVersion(authProperties.getAgreementVersion());
        volunteer.setRegisterTime(LocalDateTime.now());
        try {
            volunteerMapper.updateById(volunteer);
        } catch (DuplicateKeyException e) {
            // 并发注册同号 / 残留旧逻辑删除占位未释放唯一字段：撞 uk_phone_hash 转明确业务错误，不抛 500
            throw new BusinessException("该手机号已绑定账号，请使用手机号登录");
        }

        return new LoginVO(StpUtil.getTokenValue(), true);
    }

    /**
     * 退出登录。
     */
    public void logout() {
        StpUtil.logout();
    }
}
