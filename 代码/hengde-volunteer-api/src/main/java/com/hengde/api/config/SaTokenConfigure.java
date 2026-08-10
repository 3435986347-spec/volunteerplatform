package com.hengde.api.config;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.AdminUser;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.ResultCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    private AdminUserMapper adminUserMapper;
    private VolunteerMapper volunteerMapper;
    private DenyAllUseGate denyAllUseGate;

    @Autowired
    public void setDenyAllUseGate(DenyAllUseGate denyAllUseGate) {
        this.denyAllUseGate = denyAllUseGate;
    }

    @Autowired
    public void setAdminUserMapper(AdminUserMapper adminUserMapper) {
        this.adminUserMapper = adminUserMapper;
    }

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> {

            // 志愿者端：仅放行微信登录/发验证码/企业微信群前置校验；
            // 注册和退出需携带（游客）登录态，其余 /v/** 同样要求已登录。
            // 登录态过后再查账号状态：禁用/注销账号即便 token 未过期也在此被拦下（与管理端同口径，
            // 兜底「停用但 token 仍在」的越权窗口，覆盖改资料/换绑手机号/上传等本人写接口）。
            // 游客 status=NORMAL 仍放行（可浏览/注册）。
            SaRouter.match("/v/**")
                    .notMatch("/v/auth/login/wechat", "/v/auth/login/dev", "/v/auth/login/sms",
                            "/v/auth/login/password", "/v/auth/password/reset", "/v/auth/sms/codes",
                            "/v/auth/wechat/group-membership", "/v/auth/agreement")
                    .check(r -> {
                        StpUtil.checkLogin();
                        checkVolunteerEnabled();
                    });

            // 处置闸门（Row 73「拒绝其使用本程序」）：挂在整个 /v/** 上，默认全挡，
            // 放行清单写在 DenyAllUseGate.EXEMPT_PATHS 里（登录、奖惩记录、申诉、处置查看、站内提示）。
            // 【为什么不逐个 service 补 if】那要求今后每加一个志愿者端接口都记得补一次，
            // 漏一个没有任何征兆——V32 就是那样让「拒绝使用本程序」退化成了「限制参加活动」。
            // 【必须排在登录校验之后】它要拿当前登录态；未登录的请求上面那条已经拦下了。
            SaRouter.match("/v/**")
                    .notMatch(DenyAllUseGate.EXEMPT_PATHS)
                    .check(r -> denyAllUseGate.check(
                            SaHolder.getRequest().getRequestPath(), loggedInVolunteerIdOrNull()));

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
     * 当前登录志愿者 id；<b>未登录返回 null 而不是抛异常</b>。
     *
     * <p><b>为什么不用 {@code StpUtil.getLoginIdAsLong()}</b>：那样一来
     * {@code DenyAllUseGate.check} 里的「未登录直接放行」分支就<b>永远走不到</b>——
     * 未登录时取 id 会先抛 {@code NotLoginException}。今天不出问题，是因为所有公开的
     * {@code /v} 路径都在 {@code /v/auth/**} 下、正好被 {@link DenyAllUseGate#EXEMPT_PATHS} 豁免了；
     * 但那意味着系统的正确性依赖<b>两张分开维护的清单恰好对齐</b>——上面登录校验的
     * {@code notMatch} 公开列表，与处置闸门的豁免列表。哪天加一个 {@code /v/xxx} 公开端点、
     * 只写进前者，闸门就会在它上面取登录 id，把一个声明为公开的端点变成 401。</p>
     *
     * <p>取 null 之后两张清单就<b>解耦</b>了：闸门只管「已登录的人有没有被拒绝使用」，
     * 「这个端点要不要登录」完全交给上面那道。</p>
     *
     * <p>转换交给 Sa-Token 自己（{@code getLoginIdAsLong}）而不是手写
     * {@code Long.parseLong(String.valueOf(id))}——loginId 的存取形态是它的实现细节。</p>
     */
    private static Long loggedInVolunteerIdOrNull() {
        return StpUtil.getLoginIdDefaultNull() == null ? null : StpUtil.getLoginIdAsLong();
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

    /**
     * 校验当前志愿者登录账号是否仍处于正常状态（{@link UserStatus#NORMAL}）。
     * 禁用/注销（或 {@code @TableLogic} 致 selectById 返回 null）则登出并拦截，避免「后台已停用/注销但旧 token
     * 未过期」继续访问 /v/**（含改资料、换绑手机号、上传等本人写接口）。游客为 NORMAL，照常放行。
     */
    private void checkVolunteerEnabled() {
        Volunteer volunteer = volunteerMapper.selectById(StpUtil.getLoginIdAsLong());
        if (volunteer == null || !UserStatus.NORMAL.equals(volunteer.getStatus())) {
            StpUtil.logout();
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "账号已被禁用，请重新登录");
        }
    }
}
