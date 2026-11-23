package com.hengde.common.utils;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * 客户端 IP 提取工具（静态工具，无 bean/DI，与 {@link PasswordUtil}/{@link MaskUtil} 同风格）。
 *
 * <p>取值顺序：{@code X-Real-IP} → {@code X-Forwarded-For} 第一段 → {@code remoteAddr}。
 * <b>优先 X-Real-IP 的原因</b>：生产部署在单层 nginx 反代之后，nginx 始终以
 * {@code $remote_addr} <b>覆盖式</b>写入 X-Real-IP（客户端伪造的同名头会被覆盖，不可控）；
 * 而 XFF 若用 {@code $proxy_add_x_forwarded_for} 是<b>追加式</b>——第一段可由客户端伪造，
 * 每次换一个假 IP 即可绕开 IP 维度限流（部署/nginx.conf 已同步改为覆盖式，此处调序是双保险）。
 * 应用被直接访问（无反代）时两个头都可伪造，因此<b>取到的 IP 只作限流/审计的弱信号使用，
 * 不得作为鉴权依据</b>。</p>
 *
 * @author hengde
 */
public final class IpUtil {

    private IpUtil() {
    }

    /**
     * 提取请求的客户端 IP。
     *
     * @param request HTTP 请求；为 null 时返回 null
     * @return 客户端 IP；无法判定时退回 {@code remoteAddr}
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp) && !"unknown".equalsIgnoreCase(realIp)) {
            return realIp.trim();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded) && !"unknown".equalsIgnoreCase(forwarded)) {
            // 多级代理为逗号分隔链，第一段是最初客户端（仅在反代未设 X-Real-IP 时退而求其次）
            int comma = forwarded.indexOf(',');
            String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (StringUtils.hasText(first)) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }
}
