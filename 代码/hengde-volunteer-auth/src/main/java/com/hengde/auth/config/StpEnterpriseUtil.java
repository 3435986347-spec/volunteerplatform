package com.hengde.auth.config;

import cn.dev33.satoken.stp.StpLogic;

/**
 * 爱心企业端独立登录态（V4 爱心企业批，V4规划 D5）：第三套 {@link StpLogic}，与志愿者端 {@code StpUtil}、管理端 {@link StpAdminUtil} 的 token 互不通用。
 *
 * <p>⚠️ 与管理端同一个坑：这个 StpLogic 只在本类<b>初始化</b>时注册到 {@code SaManager}，而 {@link #TYPE} 是编译期常量、引用它不触发初始化——
 * api 的 {@code SaTokenConfigure} 启动时必须显式 {@code SaManager.putStpLogic(StpEnterpriseUtil.STP_LOGIC)}。</p>
 *
 * @author hengde
 */
public final class StpEnterpriseUtil {

    /** 企业端账号类型 */
    public static final String TYPE = "enterprise";

    /** 企业端独立 StpLogic */
    public static final StpLogic STP_LOGIC = new StpLogic(TYPE);

    private StpEnterpriseUtil() {
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

    /** 踢掉某个企业的全部登录（暂停 / 删除 / 改密码时）。 */
    public static void logout(Object loginId) {
        STP_LOGIC.logout(loginId);
    }
}
