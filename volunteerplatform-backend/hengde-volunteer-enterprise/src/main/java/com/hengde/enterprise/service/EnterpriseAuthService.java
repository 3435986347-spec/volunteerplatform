package com.hengde.enterprise.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.config.StpEnterpriseUtil;
import com.hengde.auth.service.LoginProtectionService;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.oss.FileStorageService;
import com.hengde.common.result.ResultCode;
import com.hengde.common.sms.SmsScene;
import com.hengde.common.sms.VerifyCodeService;
import com.hengde.common.utils.PasswordUtil;
import com.hengde.enterprise.constant.EnterpriseStatus;
import com.hengde.enterprise.dao.EnterpriseAccountMapper;
import com.hengde.enterprise.dto.EnterpriseDTOs;
import com.hengde.enterprise.entity.EnterpriseAccount;
import com.hengde.enterprise.vo.EnterpriseVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 爱心企业认证（V4 爱心企业批，Row 15 / Row 49，V4规划 D5）：注册（负责人手机验证码）/ 登录（第三套登录态）/ 找回与修改密码。
 *
 * <p><b>注册落「待审核」</b>，审核通过前能登录、只能看改自己的资料（放行清单在 api 的 {@code EnterpriseAccountGate}）；
 * 暂停的账号登录不了。<b>先把能校验的格式与唯一性查完、最后才核验证码</b>——验证码校验成功即作废，填错一个信用代码不该让负责人重新收一条短信。
 * 唯一性以唯一键为准（先查只是为了给出友好提示，并发注册同一账号靠 {@code uk_active_username} 兜底）。</p>
 *
 * <p>登录防爆破复用 auth 的 {@link LoginProtectionService}（企业独立计数前缀，账号维度按小写用户名）。</p>
 *
 * @author hengde
 */
@Service
public class EnterpriseAuthService {

    private static final Set<String> SCENES = Set.of(SmsScene.ENTERPRISE_REGISTER, SmsScene.ENTERPRISE_PASSWORD_RESET);

    private EnterpriseAccountMapper accountMapper;
    private VerifyCodeService verifyCodeService;
    private LoginProtectionService loginProtectionService;
    private CryptoUtil cryptoUtil;
    private FileStorageService fileStorageService;

    @Autowired
    public void setAccountMapper(EnterpriseAccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    @Autowired
    public void setVerifyCodeService(VerifyCodeService verifyCodeService) {
        this.verifyCodeService = verifyCodeService;
    }

    @Autowired
    public void setLoginProtectionService(LoginProtectionService loginProtectionService) {
        this.loginProtectionService = loginProtectionService;
    }

    @Autowired
    public void setCryptoUtil(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    @Autowired
    public void setFileStorageService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    public void sendCode(String phone, String scene, String clientIp) {
        if (!SCENES.contains(scene)) {
            throw new BusinessException("不支持的验证码场景");
        }
        if (SmsScene.ENTERPRISE_PASSWORD_RESET.equals(scene)) {
            Long bound = accountMapper.selectCount(Wrappers.<EnterpriseAccount>lambdaQuery()
                    .eq(EnterpriseAccount::getLeaderPhoneHash, cryptoUtil.hashPhone(phone)));
            if (bound == null || bound == 0) {
                throw new BusinessException("该手机号未绑定企业账号");
            }
        }
        verifyCodeService.sendCode(phone, scene, clientIp);
    }

    /** @return 新企业 id（待审核） */
    public Long register(EnterpriseDTOs.Register dto) {
        EnterpriseAccount a = new EnterpriseAccount();
        a.setName(EnterpriseSupport.requireText(dto.getName(), "请填写企业名称"));
        a.setCreditCode(EnterpriseSupport.normalizeCreditCode(dto.getCreditCode()));
        a.setLogoUrl(EnterpriseSupport.trimToNull(dto.getLogoUrl()));
        EnterpriseSupport.requireOwnImage(fileStorageService, a.getLogoUrl());
        a.setIntro(EnterpriseSupport.trimToNull(dto.getIntro()));
        a.setAddress(EnterpriseSupport.trimToNull(dto.getAddress()));
        a.setContactPhone(EnterpriseSupport.trimToNull(dto.getContactPhone()));
        a.setLeaderName(EnterpriseSupport.requireText(dto.getLeaderName(), "请填写项目负责人"));
        a.setUsername(EnterpriseSupport.requireText(dto.getUsername(), "请填写登录账号"));
        precheckUnique(a.getUsername(), a.getCreditCode());
        verifyCodeService.verify(dto.getLeaderPhone(), SmsScene.ENTERPRISE_REGISTER, dto.getSmsCode());
        a.setLeaderPhone(cryptoUtil.encrypt(dto.getLeaderPhone()));
        a.setLeaderPhoneHash(cryptoUtil.hashPhone(dto.getLeaderPhone()));
        a.setPassword(PasswordUtil.encrypt(dto.getPassword()));
        a.setStatus(EnterpriseStatus.PENDING);
        a.setSource(EnterpriseStatus.SOURCE_SELF);
        a.setSubmitTime(LocalDateTime.now().withNano(0));
        try {
            accountMapper.insert(a);
        } catch (DuplicateKeyException e) {
            throw EnterpriseSupport.duplicate(e);
        }
        return a.getId();
    }

    public EnterpriseVOs.LoginResult login(EnterpriseDTOs.Login dto, String clientIp) {
        String key = dto.getUsername().trim().toLowerCase(Locale.ROOT);
        loginProtectionService.checkEnterpriseNotLocked(key, clientIp);
        EnterpriseAccount a = accountMapper.selectOne(Wrappers.<EnterpriseAccount>lambdaQuery()
                .eq(EnterpriseAccount::getUsername, dto.getUsername().trim()));
        if (a == null || !PasswordUtil.matches(dto.getPassword(), a.getPassword())) {
            loginProtectionService.onEnterpriseLoginFailed(key, clientIp);
            throw new BusinessException(ResultCode.PASSWORD_ERROR.getCode(), "账号或密码错误");
        }
        if (Objects.equals(a.getStatus(), EnterpriseStatus.PAUSED)) {
            throw new BusinessException("企业账号已暂停" + (a.getPauseReason() == null ? "" : "：" + a.getPauseReason()) + "，请联系平台");
        }
        loginProtectionService.onEnterpriseLoginSucceeded(key);
        StpEnterpriseUtil.login(a.getId());
        accountMapper.update(null, Wrappers.<EnterpriseAccount>lambdaUpdate()
                .eq(EnterpriseAccount::getId, a.getId())
                .set(EnterpriseAccount::getLastLoginTime, LocalDateTime.now().withNano(0)));
        EnterpriseVOs.LoginResult vo = new EnterpriseVOs.LoginResult();
        vo.setToken(StpEnterpriseUtil.getTokenValue());
        vo.setEnterpriseId(a.getId());
        vo.setStatus(a.getStatus());
        vo.setStatusLabel(EnterpriseStatus.label(a.getStatus()));
        return vo;
    }

    public void logout() {
        StpEnterpriseUtil.logout();
    }

    /** 忘记密码：登录账号 + 负责人手机号 + 验证码（一个手机号可能是几家企业的负责人，所以要带账号）。改完踢掉这家企业的全部登录。 */
    public void resetPassword(EnterpriseDTOs.ResetPassword dto) {
        verifyCodeService.verify(dto.getPhone(), SmsScene.ENTERPRISE_PASSWORD_RESET, dto.getSmsCode());
        EnterpriseAccount a = accountMapper.selectOne(Wrappers.<EnterpriseAccount>lambdaQuery()
                .eq(EnterpriseAccount::getUsername, dto.getUsername().trim())
                .eq(EnterpriseAccount::getLeaderPhoneHash, cryptoUtil.hashPhone(dto.getPhone())));
        if (a == null) {
            throw new BusinessException("账号与负责人手机号对不上");
        }
        updatePassword(a.getId(), dto.getNewPassword());
    }

    public void changePassword(Long enterpriseId, EnterpriseDTOs.ChangePassword dto) {
        EnterpriseAccount a = accountMapper.selectById(enterpriseId);
        if (a == null || !PasswordUtil.matches(dto.getOldPassword(), a.getPassword())) {
            throw new BusinessException("原密码不正确");
        }
        updatePassword(enterpriseId, dto.getNewPassword());
    }

    private void updatePassword(Long id, String newPassword) {
        accountMapper.update(null, Wrappers.<EnterpriseAccount>lambdaUpdate()
                .eq(EnterpriseAccount::getId, id)
                .set(EnterpriseAccount::getPassword, PasswordUtil.encrypt(newPassword))
                .set(EnterpriseAccount::getUpdateTime, LocalDateTime.now()));
        StpEnterpriseUtil.logout(id);
    }

    private void precheckUnique(String username, String creditCode) {
        Long u = accountMapper.selectCount(Wrappers.<EnterpriseAccount>lambdaQuery().eq(EnterpriseAccount::getUsername, username));
        if (u != null && u > 0) {
            throw new BusinessException("这个登录账号已经被注册了，请换一个");
        }
        Long c = accountMapper.selectCount(Wrappers.<EnterpriseAccount>lambdaQuery().eq(EnterpriseAccount::getCreditCode, creditCode));
        if (c != null && c > 0) {
            throw new BusinessException("这个统一社会信用代码已经入驻过了；如需找回账号请联系平台");
        }
    }
}
