package com.hengde.api.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.entity.AdminUser;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.ResultCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    private AdminUserMapper adminUserMapper;

    @Autowired
    public void setAdminUserMapper(AdminUserMapper adminUserMapper) {
        this.adminUserMapper = adminUserMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> {

            // 志愿者端：仅放行微信登录/发验证码/企业微信群前置校验；
            // 注册和退出需携带（游客）登录态，其余 /v/** 同样要求已登录
            SaRouter.match("/v/**")
                    .notMatch("/v/auth/login/wechat", "/v/auth/login/dev", "/v/auth/login/sms",
                            "/v/auth/login/password", "/v/auth/password/reset", "/v/auth/sms/codes",
                            "/v/auth/wechat/group-membership", "/v/auth/agreement")
                    .check(r -> StpUtil.checkLogin());

            // 管理端：放行登录 + 忘记密码两步（发验证码/重置密码）；其余用独立的管理端登录态校验，
            // 与志愿者端 StpUtil 隔离，志愿者 token 无法通过此校验。登录态过后再查账号状态，
            // 禁用/注销账号即便 token 未过期也在此被拦下（兜底「停用但 token 仍在」的越权窗口）
            SaRouter.match("/a/**")
                    .notMatch("/a/auth/login", "/a/auth/sms/codes", "/a/auth/password/reset")
                    .check(r -> {
                        StpAdminUtil.checkLogin();
                        checkAdminEnabled();
                    });

            // 企业端（V1 暂缓，预留拦截链占位）
            SaRouter.match("/e/**")
                    .notMatch("/e/auth/login")
                    .check(r -> StpUtil.checkLogin());

        })).addPathPatterns("/**");
    }

    /**
     * 校验当前管理端登录账号是否仍处于启用状态（status=0）。
     * 禁用（status!=0）或注销（{@code @TableLogic} 致 selectById 返回 null）则登出并拦截，
     * 避免「后台已停用但旧 token 未过期」的请求继续放行。
     */
    private void checkAdminEnabled() {
        AdminUser admin = adminUserMapper.selectById(StpAdminUtil.getLoginIdAsLong());
        if (admin == null || !Integer.valueOf(0).equals(admin.getStatus())) {
            StpAdminUtil.logout();
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "账号已被禁用，请重新登录");
        }
    }
}
