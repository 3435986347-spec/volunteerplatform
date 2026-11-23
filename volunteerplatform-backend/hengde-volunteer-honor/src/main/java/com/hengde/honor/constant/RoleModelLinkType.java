package com.hengde.honor.constant;

import org.springframework.util.StringUtils;

/**
 * 榜样的跳转类型。
 *
 * <p><b>为什么要有这一列</b>：此前只有 {@code link_url} 一个字段，小程序拿到一串地址
 * 无从判断该用哪种方式打开——小程序内部路由要用 {@code navigateTo}、备案网页要开 WebView、
 * 外部链接在小程序里根本打不开只能让用户复制。靠前缀猜（是不是 https 开头）会把
 * 「要用 WebView 打开的推文」和「只能复制的外部链接」混成一类，而这两者行为完全不同。</p>
 *
 * <p><b>码值与名字同时下发</b>：库里存 int（与 {@code publicity_banner.link_type} 等既有列同形），
 * 出参额外带一个稳定的名字（{@code NONE}/{@code PAGE}/{@code WEB}/{@code EXTERNAL}）供小程序 switch，
 * 免得两端各自维护一份「1 是什么」的映射——那种映射迟早漂开。</p>
 *
 * @author hengde
 */
public final class RoleModelLinkType {

    private RoleModelLinkType() {
    }

    /** 不跳转，纯展示 */
    public static final int NONE = 0;
    /** 小程序内部页面，link_url 存路由路径 */
    public static final int PAGE = 1;
    /** 网页，由 WebView 打开；必须是 https，且域名须在微信业务域名白名单内 */
    public static final int WEB = 2;
    /** 外部链接，小程序内打不开，只能复制 */
    public static final int EXTERNAL = 3;

    /**
     * 是否为合法跳转类型码。
     *
     * @param linkType 跳转类型码
     * @return 合法返回 true
     */
    public static boolean isValid(Integer linkType) {
        return linkType != null && linkType >= NONE && linkType <= EXTERNAL;
    }

    /**
     * 该类型是否必须带链接。
     *
     * @param linkType 跳转类型码
     * @return 需要链接返回 true
     */
    public static boolean requiresUrl(Integer linkType) {
        return linkType != null && linkType != NONE;
    }

    /**
     * 稳定的英文名，直接下发给小程序 switch 用。
     *
     * @param linkType 跳转类型码
     * @return 英文名；未知码回 {@code NONE}（未知一律降级成「不跳转」，
     *         宁可少一个入口，也不要让客户端拿着不认识的码去猜怎么打开）
     */
    public static String nameOf(Integer linkType) {
        if (linkType == null) {
            return "NONE";
        }
        return switch (linkType) {
            case PAGE -> "PAGE";
            case WEB -> "WEB";
            case EXTERNAL -> "EXTERNAL";
            default -> "NONE";
        };
    }

    /**
     * 是否是 https 链接。
     *
     * <p>{@link #WEB} 走 WebView，微信要求业务域名必须是 https；http 地址配上去
     * 在体验版能打开、正式版直接白屏，属于「上线才发现」的那类问题，故在保存时就拦。</p>
     *
     * @param url 链接
     * @return 是 https 返回 true
     */
    public static boolean isHttps(String url) {
        return StringUtils.hasText(url) && url.trim().toLowerCase().startsWith("https://");
    }
}
