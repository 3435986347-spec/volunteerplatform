package com.hengde.social.ws;

import com.hengde.social.service.SocialChatPushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 私信的 WebSocket 连接（V4 私信批，D8）。
 *
 * <p><b>这条连接只用来「收」</b>：发消息仍走 HTTP（{@code POST /v/social/chats/{peerId}/messages}）——
 * 落库、闸门、关键词、限额那一整套都在服务层，从 WebSocket 再走一遍等于把同一段逻辑写两份。
 * 客户端往这里发什么都只回一个 pong，免得断线检测的心跳把连接判死。</p>
 *
 * <p>身份在<b>握手</b>时由 {@link SocialChatHandshakeInterceptor} 用 Sa-Token 认过，
 * 之后只信 session attributes 里的那个 id——连上之后客户端再报自己是谁，是不能信的。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class SocialChatWebSocketHandler extends TextWebSocketHandler {

    /** 握手时放进 session attributes 的键 */
    public static final String ATTR_VOLUNTEER_ID = "volunteerId";

    private SocialChatPushService pushService;

    @Autowired
    public void setPushService(SocialChatPushService pushService) {
        this.pushService = pushService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long me = volunteerId(session);
        if (me == null) {
            close(session);
            return;
        }
        pushService.register(me, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 心跳：原样回一个 pong，不解析内容（发消息走 HTTP）
        if (session.isOpen()) {
            session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        pushService.unregister(volunteerId(session), session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("私信连接异常 sessionId={}", session.getId(), exception);
        pushService.unregister(volunteerId(session), session);
    }

    private static Long volunteerId(WebSocketSession session) {
        Object v = session.getAttributes().get(ATTR_VOLUNTEER_ID);
        return v instanceof Long id ? id : null;
    }

    private static void close(WebSocketSession session) {
        try {
            session.close(CloseStatus.NOT_ACCEPTABLE);
        } catch (Exception ignored) {
            // 关不上就算了，连接迟早会被对端或容器回收
        }
    }
}
