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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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

    private VolunteerMapper volunteerMapper;
    private WxMaService wxMaService;
    private VerifyCodeService verifyCodeService;
    private CryptoUtil cryptoUtil;
    private RealNameService realNameService;
    private WeworkGroupService weworkGroupService;
    private AuthProperties authProperties;

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
        } else if (volunteer.getStatus() != null && volunteer.getStatus().equals(UserStatus.BANNED)) {
            throw new BusinessException("账号已被禁用");
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
        } else if (volunteer.getStatus() != null && volunteer.getStatus().equals(UserStatus.BANNED)) {
            throw new BusinessException("账号已被禁用");
        }

        // 造一个可用的「已实名」测试身份：填姓名 + 成年生日 + 性别，避免年龄/性别资格校验取到 null
        if (registered && volunteer.getRegisterTime() == null) {
            volunteer.setRealName("测试志愿者-" + safeKey);
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
     * 发送注册短信验证码。
     */
    public void sendRegisterSmsCode(String phone) {
        verifyCodeService.sendCode(phone, SmsScene.REGISTER);
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

        // 1. 短信验证码
        verifyCodeService.verify(dto.getPhone(), SmsScene.REGISTER, dto.getSmsCode());

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

        // 5. 未成年需紧急联系人，且不能与本人手机号相同
        int age = IdcardUtil.getAgeByIdCard(dto.getIdCardNo());
        if (age < 18) {
            if (!StringUtils.hasText(dto.getEmergencyContactName())
                    || !StringUtils.hasText(dto.getEmergencyContactPhone())) {
                throw new BusinessException("未成年志愿者须填写紧急联系人");
            }
            if (dto.getPhone().equals(dto.getEmergencyContactPhone())) {
                throw new BusinessException("紧急联系人电话不能与本人手机号相同");
            }
        }

        // 6. 填充并加密落库
        volunteer.setRealName(dto.getRealName());
        volunteer.setIdCardNo(cryptoUtil.encrypt(dto.getIdCardNo()));
        volunteer.setIdCardHash(idCardHash);
        volunteer.setPhone(cryptoUtil.encrypt(dto.getPhone()));
        volunteer.setPhoneHash(cryptoUtil.hashPhone(dto.getPhone()));
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
        volunteer.setAddress(dto.getAddress());
        volunteer.setIVolunteerCodeUrl(dto.getIVolunteerCodeUrl());
        volunteer.setAvatarUrl(dto.getAvatarUrl());
        volunteer.setEmergencyContactName(dto.getEmergencyContactName());
        volunteer.setEmergencyContactPhone(cryptoUtil.encrypt(dto.getEmergencyContactPhone()));
        volunteer.setSignatureUrl(dto.getSignatureUrl());
        // 入库标记：记录注册时服务端当前的协议版本（手写签名图本身是签署凭据）
        volunteer.setSignedAgreementVersion(authProperties.getAgreementVersion());
        volunteer.setRegisterTime(LocalDateTime.now());
        volunteerMapper.updateById(volunteer);

        return new LoginVO(StpUtil.getTokenValue(), true);
    }

    /**
     * 退出登录。
     */
    public void logout() {
        StpUtil.logout();
    }
}
