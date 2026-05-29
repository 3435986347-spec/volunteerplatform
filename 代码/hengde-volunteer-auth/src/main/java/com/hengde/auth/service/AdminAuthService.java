package com.hengde.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.dto.AdminChangePasswordDTO;
import com.hengde.auth.dto.AdminLoginDTO;
import com.hengde.auth.dto.AdminResetPasswordDTO;
import com.hengde.auth.entity.AdminUser;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.ResultCode;
import com.hengde.common.sms.SmsScene;
import com.hengde.common.sms.VerifyCodeService;
import com.hengde.common.utils.PasswordUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 管理端认证服务：账号密码登录、找回密码、修改密码、退出。
 *
 * <p>登录态走 {@link StpAdminUtil}（loginType=admin），与志愿者端隔离。依赖按约定 setter 注入。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class AdminAuthService {

    private AdminUserMapper adminUserMapper;
    private VerifyCodeService verifyCodeService;

    @Autowired
    public void setAdminUserMapper(AdminUserMapper adminUserMapper) {
        this.adminUserMapper = adminUserMapper;
    }

    @Autowired
    public void setVerifyCodeService(VerifyCodeService verifyCodeService) {
        this.verifyCodeService = verifyCodeService;
    }

    /**
     * 账号密码登录，返回 token。
     */
    public String login(AdminLoginDTO dto) {
        AdminUser admin = adminUserMapper.selectOne(
                Wrappers.<AdminUser>lambdaQuery().eq(AdminUser::getUsername, dto.getUsername()));
        if (admin == null || !PasswordUtil.matches(dto.getPassword(), admin.getPassword())) {
            throw new BusinessException(ResultCode.PASSWORD_ERROR.getCode(), "账号或密码错误");
        }
        if (admin.getStatus() != null && admin.getStatus().equals(UserStatus.BANNED)) {
            throw new BusinessException("账号已被禁用");
        }
        StpAdminUtil.login(admin.getId());

        admin.setLastLoginTime(LocalDateTime.now());
        adminUserMapper.updateById(admin);
        return StpAdminUtil.getTokenValue();
    }

    /**
     * 发送找回密码短信验证码（手机号须已绑定后台账号）。
     */
    public void sendResetSmsCode(String phone) {
        AdminUser admin = adminUserMapper.selectOne(
                Wrappers.<AdminUser>lambdaQuery().eq(AdminUser::getPhone, phone));
        if (admin == null) {
            throw new BusinessException("该手机号未绑定后台账号");
        }
        verifyCodeService.sendCode(phone, SmsScene.RESET_PASSWORD);
    }

    /**
     * 凭短信验证码重置密码。
     */
    public void resetPassword(AdminResetPasswordDTO dto) {
        verifyCodeService.verify(dto.getPhone(), SmsScene.RESET_PASSWORD, dto.getSmsCode());
        AdminUser admin = adminUserMapper.selectOne(
                Wrappers.<AdminUser>lambdaQuery().eq(AdminUser::getPhone, dto.getPhone()));
        if (admin == null) {
            throw new BusinessException("该手机号未绑定后台账号");
        }
        admin.setPassword(PasswordUtil.encrypt(dto.getNewPassword()));
        adminUserMapper.updateById(admin);
    }

    /**
     * 已登录修改密码。
     */
    public void changePassword(AdminChangePasswordDTO dto) {
        long adminId = StpAdminUtil.getLoginIdAsLong();
        AdminUser admin = adminUserMapper.selectById(adminId);
        if (admin == null || !PasswordUtil.matches(dto.getOldPassword(), admin.getPassword())) {
            throw new BusinessException("原密码错误");
        }
        admin.setPassword(PasswordUtil.encrypt(dto.getNewPassword()));
        adminUserMapper.updateById(admin);
    }

    /**
     * 退出登录。
     */
    public void logout() {
        StpAdminUtil.logout();
    }
}
