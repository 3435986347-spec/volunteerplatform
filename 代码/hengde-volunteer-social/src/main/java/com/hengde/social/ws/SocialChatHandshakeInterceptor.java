package com.hengde.social.ws;

import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * 私信 WebSocket 的握手认证（V4 私信批）。
 *
 * <p><b>token 从查询串取</b>（{@code ?token=}）：浏览器与小程序的 WebSocket API 都不让自定义握手请求头，
 * 这是它们唯一能带身份的地方。认下来的志愿者 id 放进 session attributes，之后<b>只信它</b>。</p>
 *
 * <p>⚠️ 查询串会进 nginx / 网关的访问日志，所以这个 token 与普通接口用的是同一个、有过期时间的登录 token，
 * 不另发长期凭据；线上要把 {@code /ws/} 的 access_log 关掉或脱敏（见部署说明）。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class SocialChatHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = tokenOf(request.getURI());
        if (token == null || token.isBlank()) {
            return false;
        }
        Object loginId;
        try {
            loginId = StpUtil.getLoginIdByToken(token);
        } catch (RuntimeException e) {
            log.debug("私信握手取登录态失败", e);
            return false;
        }
        if (loginId == null) {
            return false;
        }
        try {
            attributes.put(SocialChatWebSocketHandler.ATTR_VOLUNTEER_ID, Long.parseLong(loginId.toString()));
        } catch (NumberFormatException e) {
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }

    static String tokenOf(URI uri) {
        String query = uri == null ? null : uri.getQuery();
        if (query == null) {
            return null;
        }
        for (String part : query.split("&")) {
            int i = part.indexOf('=');
            if (i > 0 && "token".equals(part.substring(0, i))) {
                return java.net.URLDecoder.decode(part.substring(i + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
