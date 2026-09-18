package com.hengde.api.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.auth.config.StpEnterpriseUtil;
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
    private EnterpriseAccountGate enterpriseAccountGate;

    @Autowired
    public void setEnterpriseAccountGate(EnterpriseAccountGate enterpriseAccountGate) {
        this.enterpriseAccountGate = enterpriseAccountGate;
    }

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
        // ⚠️ 启动时就把管理端 StpLogic 注册进 SaManager，不能等到第一次用 StpAdminUtil。
        // SaInterceptor 先做注解校验、后跑下面的路由函数；而注解里的 type="admin" 要靠 SaManager 查 StpLogic，
        // 它只在 StpAdminUtil 类初始化（new StpLogic 时）才注册——StpAdminUtil.TYPE 是编译期常量，引用它不触发初始化。
        // 于是重启后第一个打到带 @SaCheckPermission(type="admin") 端点的请求（不论带不带 token）
        // 都会抛「未能获取对应StpLogic，type=admin」成 500，未登录的也拿不到 401，控制台跳不回登录页；
        // 直到有人碰巧先调了登录或 /a/auth/me 才自愈。TradeCallbackApiTest 的反向断言撞出来的
        SaManager.putStpLogic(StpAdminUtil.STP_LOGIC);
        // 企业端同理（V4 爱心企业批）：@SaCheckLogin(type="enterprise") 之类的注解同样要靠 SaManager 查到它
        SaManager.putStpLogic(StpEnterpriseUtil.STP_LOGIC);
        registry.addInterceptor(new SaInterceptor(handler -> {

            // 志愿者端：仅放行微信登录/发验证码/企业微信群前置校验；
            // 注册和退出需携带（游客）登录态，其余 /v/** 同样要求已登录。
            // 登录态过后再查账号状态：注销账号即便 token 未过期也在此被拦下
            // （兜底「停用但 token 仍在」的越权窗口，覆盖改资料/换绑手机号/上传等本人写接口）。
            // 【禁用账号不再一刀切】协会 2026-08-11 第 5 条要求「只给禁用账号开个小口子、
            // 只能看奖惩和提申诉」，放行清单见 BannedAccountGate.EXEMPT_PATHS。
            // 游客 status=NORMAL 仍放行（可浏览/注册）。
            SaRouter.match("/v/**")
                    .notMatch("/v/auth/login/wechat", "/v/auth/login/dev", "/v/auth/login/sms",
                            "/v/auth/login/password", "/v/auth/password/reset", "/v/auth/sms/codes",
                            "/v/auth/wechat/group-membership", "/v/auth/agreement",
                            // 志愿者证扫码核验（V4 志愿者证批，Row 26）：扫码的人不一定是本平台用户；只收随机令牌、不收志愿者 id
                            "/v/user/volunteer-cards/verify")
                    .check(r -> {
                        StpUtil.checkLogin();
                        checkVolunteerEnabled(SaHolder.getRequest().getRequestPath());
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

            // 企业端（V4 爱心企业批，第三套登录态）：注册 / 登录 / 发验证码 / 找回密码公开；其余要企业登录态，
            // 再按账号当前状态放行（暂停 / 删除即踢出，待审核 / 驳回只能用认证与「我的企业」），判定在 EnterpriseAccountGate。
            // ⚠️ 志愿者 token 与管理端 token 在这里都过不了（各自的 StpLogic 查不到登录态）。
            SaRouter.match("/e/**")
                    .notMatch("/e/auth/login", "/e/auth/register", "/e/auth/sms/codes", "/e/auth/password/reset")
                    .check(r -> {
                        StpEnterpriseUtil.checkLogin();
                        enterpriseAccountGate.check(SaHolder.getRequest().getRequestPath(), StpEnterpriseUtil.getLoginIdAsLong());
                    });

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
     * 校验当前志愿者登录账号的状态，<b>三分而不是二分</b>。游客为 NORMAL，照常放行。
     *
     * <ul>
     *   <li><b>行不存在</b>（或 {@code @TableLogic} 致 {@code selectById} 返回 null）
     *       / <b>已注销</b>（{@link UserStatus#DELETED}）→ <b>登出 + 403</b>。
     *       兜住「后台已注销但旧 token 未过期」的越权窗口。</li>
     *   <li><b>已禁用</b>（{@link UserStatus#BANNED}）→ 仅放行
     *       {@link BannedAccountGate#EXEMPT_PATHS}，其余 403。</li>
     *   <li><b>正常</b> → 放行。</li>
     * </ul>
     *
     * <p><b>禁用态绝不能 {@code logout()}</b>，这是本方法最容易写错的一处：一旦登出，
     * 他手上的 token 立刻作废，<b>连放行清单里的申诉接口都会变成 401</b>——
     * 小口子当场被自己关上，而表面上清单还写得好好的。注销那一支必须登出（账号已经没了），
     * 禁用这一支必须保留登录态（他还要用它去申诉）。两支的差别不是文案，是有没有那一行 {@code logout}。</p>
     *
     * <p><b>为什么判定要带上路径</b>：禁用不再是「一律拦掉」，而是「除了这几条都拦掉」，
     * 那就必须知道当前请求是哪一条。路径取 {@code SaHolder.getRequest().getRequestPath()}，
     * 与 {@code SaRouter.match} 同源——两处取路径的方式若不一致，
     * 清单会在某些前缀/编码形态下静默失配。</p>
     *
     * @param path 请求路径（不含 contextPath）
     */
    private void checkVolunteerEnabled(String path) {
        Volunteer volunteer = volunteerMapper.selectById(StpUtil.getLoginIdAsLong());
        Integer status = volunteer == null ? null : volunteer.getStatus();
        switch (decide(status, path)) {
            case PASS -> {
            }
            case DENY_KEEP_SESSION -> throw new BusinessException(ResultCode.FORBIDDEN.getCode(),
                    "账号已被禁用，暂不能使用该功能。可在奖惩记录中查看处罚详情与解除时间，并在申诉期内提交申诉");
            case DENY_AND_LOGOUT -> {
                StpUtil.logout();
                throw new BusinessException(ResultCode.FORBIDDEN.getCode(),
                        UserStatus.DELETED.equals(status) ? "账号已注销，请重新登录" : "账号状态异常，请重新登录");
            }
        }
    }

    /** {@link #decide} 的三种结论。{@code DENY_*} 的区别只在<b>要不要登出</b>，而那一位判反了没有任何征兆。 */
    enum Access {
        /** 放行 */
        PASS,
        /** 拒绝，<b>但保留登录态</b>——禁用账号还要用这个 token 去申诉 */
        DENY_KEEP_SESSION,
        /** 拒绝并登出——账号已经没了（注销），或状态是我们不认识的值 */
        DENY_AND_LOGOUT
    }

    /**
     * 志愿者端访问判定，<b>抽成纯函数只为让它能被测试直接钉住</b>。
     *
     * <p>与 {@code DenyAllUseGate.isExempt}、{@code AdminRewardPunishController.assertScopeAllowed}
     * 同一理由：这里每一位判错都<b>不会抛异常、不会进日志</b>，只会体现在某个被禁用的人身上。
     * 尤其是 {@link Access#DENY_KEEP_SESSION} 与 {@link Access#DENY_AND_LOGOUT} 的区别——
     * 禁用那一支若误登出，他手上的 token 立刻作废，<b>连放行清单里的申诉接口都会变成 401</b>，
     * 小口子当场被自己关上，而清单本身看着还写得好好的。</p>
     *
     * <p><b>兜底必须是拒绝而不是放行</b>：写成「只拦 DELETED」的话，将来多一个状态取值
     * （或某行 {@code status} 为 NULL）就会静默变成放行——这一支是白名单的反面，不是异常分支。</p>
     *
     * @param status 当前登录志愿者的账号状态；行不存在时为 {@code null}
     * @param path   请求路径（不含 contextPath）
     */
    static Access decide(Integer status, String path) {
        if (UserStatus.NORMAL.equals(status)) {
            return Access.PASS;
        }
        if (UserStatus.BANNED.equals(status)) {
            return BannedAccountGate.isExempt(path) ? Access.PASS : Access.DENY_KEEP_SESSION;
        }
        return Access.DENY_AND_LOGOUT;
    }
}
