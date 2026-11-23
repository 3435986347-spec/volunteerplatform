package com.hengde.api.config;

import com.hengde.social.constant.SocialChatCodes;
import com.hengde.social.ws.SocialChatHandshakeInterceptor;
import com.hengde.social.ws.SocialChatWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点注册（V4 私信批）。
 *
 * <p>按「主动改变应用行为的配置放 api」的约定落在这里：处理器与握手认证在 social 模块，
 * 领域模块的测试上下文因此<b>不会</b>起 WebSocket 容器。</p>
 *
 * <p>⚠️ 路径 {@code /ws/social/chat} 带 context-path 之后是 {@code /api/ws/social/chat}；
 * 它不在 {@code /v} {@code /a} {@code /e} 之下，所以 Sa-Token 的路由拦截器不管它——
 * <b>身份由握手时的 token 参数认</b>（见 {@code SocialChatHandshakeInterceptor}）。
 * 反过来说，往那个拦截器里加一条全局 match 会把握手一起挡掉（trade 回调那一课）。</p>
 *
 * <p>线上 nginx 要给这个路径放行 {@code Upgrade}/{@code Connection} 头，见《部署说明》。</p>
 *
 * @author hengde
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private SocialChatWebSocketHandler chatHandler;
    private SocialChatHandshakeInterceptor handshakeInterceptor;

    @Autowired
    public void setChatHandler(SocialChatWebSocketHandler chatHandler) {
        this.chatHandler = chatHandler;
    }

    @Autowired
    public void setHandshakeInterceptor(SocialChatHandshakeInterceptor handshakeInterceptor) {
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatHandler, SocialChatCodes.WS_PATH)
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
