package com.hengde.enterprise.controller;

import com.hengde.auth.config.StpEnterpriseUtil;
import com.hengde.common.result.Result;
import com.hengde.common.utils.IpUtil;
import com.hengde.enterprise.dto.EnterpriseDTOs;
import com.hengde.enterprise.service.EnterpriseAuthService;
import com.hengde.enterprise.vo.EnterpriseVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业端-认证（{@code /e/auth}，V4 爱心企业批）。注册 / 登录 / 发验证码 / 找回密码是公开的（登记在 api 的 {@code SaTokenConfigure}），其余需企业登录态。
 *
 * @author hengde
 */
@Tag(name = "企业端-认证")
@RestController
@RequestMapping("/e/auth")
public class EnterpriseAuthController {

    private EnterpriseAuthService authService;

    @Autowired
    public void setAuthService(EnterpriseAuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "发短信验证码（enterprise-register 入驻注册 / enterprise-password-reset 找回密码）")
    @PostMapping("/sms/codes")
    public Result<Void> sendCode(@Valid @RequestBody EnterpriseDTOs.SmsCode dto, HttpServletRequest request) {
        authService.sendCode(dto.getPhone(), dto.getScene(), IpUtil.getClientIp(request));
        return Result.ok();
    }

    @Operation(summary = "入驻注册（落待审核；审核通过前登录后只能看改自己的资料）")
    @PostMapping("/register")
    public Result<Long> register(@Valid @RequestBody EnterpriseDTOs.Register dto) {
        return Result.ok(authService.register(dto));
    }

    @Operation(summary = "企业登录（暂停的登录不了；待审核 / 驳回能登录看状态）")
    @PostMapping("/login")
    public Result<EnterpriseVOs.LoginResult> login(@Valid @RequestBody EnterpriseDTOs.Login dto, HttpServletRequest request) {
        return Result.ok(authService.login(dto, IpUtil.getClientIp(request)));
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.ok();
    }

    @Operation(summary = "凭负责人手机验证码重置密码（带登录账号；改完踢掉全部登录）")
    @PutMapping("/password/reset")
    public Result<Void> resetPassword(@Valid @RequestBody EnterpriseDTOs.ResetPassword dto) {
        authService.resetPassword(dto);
        return Result.ok();
    }

    @Operation(summary = "修改密码（改完踢掉全部登录，含当前这个）")
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody EnterpriseDTOs.ChangePassword dto) {
        authService.changePassword(StpEnterpriseUtil.getLoginIdAsLong(), dto);
        return Result.ok();
    }
}
