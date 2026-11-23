package com.hengde.auth.config;

import cn.dev33.satoken.stp.StpLogic;

/**
 * 管理端登录态工具（Sa-Token 多账号隔离）。
 *
 * <p>管理端用独立的 {@link StpLogic}（loginType=<b>admin</b>），与志愿者端默认的
 * {@code StpUtil}（loginType=login）互相隔离：志愿者 token 无法通过管理端的登录校验，反之亦然。
 * 管理端登录/校验/退出一律走本类，路由拦截里 {@code /a/**} 也用 {@link #checkLogin()}。</p>
 *
 * @author hengde
 */
public final class StpAdminUtil {

    /** 管理端账号类型 */
    public static final String TYPE = "admin";

    /** 管理端独立 StpLogic（构造即注册到 SaManager） */
    public static final StpLogic STP_LOGIC = new StpLogic(TYPE);

    private StpAdminUtil() {
    }

    public static void login(Object loginId) {
        STP_LOGIC.login(loginId);
    }

    public static String getTokenValue() {
        return STP_LOGIC.getTokenValue();
    }

    public static long getLoginIdAsLong() {
        return STP_LOGIC.getLoginIdAsLong();
    }

    public static boolean isLogin() {
        return STP_LOGIC.isLogin();
    }

    public static void checkLogin() {
        STP_LOGIC.checkLogin();
    }

    public static void logout() {
        STP_LOGIC.logout();
    }
}
