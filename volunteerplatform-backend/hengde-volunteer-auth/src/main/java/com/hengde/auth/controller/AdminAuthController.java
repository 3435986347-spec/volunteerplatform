package com.hengde.auth.controller;

import com.hengde.auth.dto.AdminChangePasswordDTO;
import com.hengde.auth.dto.AdminLoginDTO;
import com.hengde.auth.dto.AdminResetPasswordDTO;
import com.hengde.auth.dto.SmsCodeDTO;
import com.hengde.auth.service.AdminAuthService;
import com.hengde.common.result.Result;
import com.hengde.common.utils.IpUtil;
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
 * 管理端认证接口。登录/找回密码公开，其余需管理端登录态。
 *
 * @author hengde
 */
@Tag(name = "管理端-认证")
@RestController
@RequestMapping("/a/auth")
public class AdminAuthController {

    private AdminAuthService adminAuthService;

    @Autowired
    public void setAdminAuthService(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @Operation(summary = "账号密码登录")
    @PostMapping("/login")
    public Result<String> login(@RequestBody @Valid AdminLoginDTO dto, HttpServletRequest request) {
        return Result.ok(adminAuthService.login(dto, IpUtil.getClientIp(request)));
    }

    @Operation(summary = "发送短信验证码（找回密码）")
    @PostMapping("/sms/codes")
    public Result<Void> sendSmsCode(@RequestBody @Valid SmsCodeDTO dto, HttpServletRequest request) {
        adminAuthService.sendResetSmsCode(dto.getPhone(), IpUtil.getClientIp(request));
        return Result.ok();
    }

    @Operation(summary = "凭验证码重置密码")
    @PutMapping("/password/reset")
    public Result<Void> resetPassword(@RequestBody @Valid AdminResetPasswordDTO dto) {
        adminAuthService.resetPassword(dto);
        return Result.ok();
    }

    @Operation(summary = "修改密码")
    @PutMapping("/password")
    public Result<Void> changePassword(@RequestBody @Valid AdminChangePasswordDTO dto) {
        adminAuthService.changePassword(dto);
        return Result.ok();
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<Void> logout() {
        adminAuthService.logout();
        return Result.ok();
    }
}
