package com.hengde.auth.controller;

import com.hengde.auth.dto.DevLoginDTO;
import com.hengde.auth.dto.RegisterDTO;
import com.hengde.auth.dto.SmsCodeDTO;
import com.hengde.auth.dto.WechatLoginDTO;
import com.hengde.auth.integration.WeworkGroupService;
import com.hengde.auth.service.VolunteerAuthService;
import com.hengde.auth.vo.AgreementVO;
import com.hengde.auth.vo.GroupMembershipVO;
import com.hengde.auth.vo.LoginVO;
import com.hengde.common.result.Result;
import com.hengde.common.utils.IpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 志愿者端认证接口。全部公开（路由白名单 /v/auth/**）。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-认证")
@RestController
@RequestMapping("/v/auth")
public class VolunteerAuthController {

    private VolunteerAuthService volunteerAuthService;
    private WeworkGroupService weworkGroupService;

    @Autowired
    public void setVolunteerAuthService(VolunteerAuthService volunteerAuthService) {
        this.volunteerAuthService = volunteerAuthService;
    }

    @Autowired
    public void setWeworkGroupService(WeworkGroupService weworkGroupService) {
        this.weworkGroupService = weworkGroupService;
    }

    @Operation(summary = "发送短信验证码（注册）")
    @PostMapping("/sms/codes")
    public Result<Void> sendSmsCode(@RequestBody @Valid SmsCodeDTO dto, HttpServletRequest request) {
        volunteerAuthService.sendRegisterSmsCode(dto.getPhone(), IpUtil.getClientIp(request));
        return Result.ok();
    }

    @Operation(summary = "获取志愿者协议文本（注册前阅读，含版本号）")
    @GetMapping("/agreement")
    public Result<AgreementVO> agreement() {
        return Result.ok(volunteerAuthService.getAgreement());
    }

    @Operation(summary = "微信小程序登录")
    @PostMapping("/login/wechat")
    public Result<LoginVO> wechatLogin(@RequestBody @Valid WechatLoginDTO dto) {
        return Result.ok(volunteerAuthService.wechatLogin(dto.getCode()));
    }

    @Operation(summary = "开发登录（跳过微信，仅 dev-login-enabled=true 时可用，生产禁用）")
    @PostMapping("/login/dev")
    public Result<LoginVO> devLogin(@RequestBody(required = false) @Valid DevLoginDTO dto) {
        String key = dto == null ? null : dto.getKey();
        boolean registered = dto != null && Boolean.TRUE.equals(dto.getRegistered());
        return Result.ok(volunteerAuthService.devLogin(key, registered));
    }

    @Operation(summary = "志愿者实名注册")
    @PostMapping("/register")
    public Result<LoginVO> register(@RequestBody @Valid RegisterDTO dto) {
        return Result.ok(volunteerAuthService.register(dto));
    }

    @Operation(summary = "企业微信群成员前置校验")
    @GetMapping("/wechat/group-membership")
    public Result<GroupMembershipVO> groupMembership(@RequestParam String phone) {
        boolean member = volunteerAuthService.checkGroupMembership(phone);
        return Result.ok(new GroupMembershipVO(member, member ? null : weworkGroupService.getGroupQrUrl()));
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<Void> logout() {
        volunteerAuthService.logout();
        return Result.ok();
    }
}
