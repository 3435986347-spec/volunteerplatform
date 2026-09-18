package com.hengde.social.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 私信的实时推送（V4规划 D8）：WebSocket 连接登记处 + 推一条消息。
 *
 * <p><b>推送永远是「锦上添花」</b>——消息已经落库，推不出去只是对方晚一点看到（他一拉取就有）。
 * 所以这里的每一个失败都只记日志：把推送失败抛回业务，等于让一个断开的手机连接决定别人的私信发不发得出去。</p>
 *
 * <p>一个人可能同时开着几个端（小程序、后台预览），所以按 volunteerId 存一组 session；
 * 连接关闭时要从组里摘掉，摘干净了把整个 key 删掉，免得在线名单越积越大。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class SocialChatPushService {

    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public void register(Long volunteerId, WebSocketSession session) {
        if (volunteerId == null || session == null) {
            return;
        }
        sessions.computeIfAbsent(volunteerId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unregister(Long volunteerId, WebSocketSession session) {
        if (volunteerId == null || session == null) {
            return;
        }
        sessions.computeIfPresent(volunteerId, (k, set) -> {
            set.remove(session);
            return set.isEmpty() ? null : set;
        });
    }

    /** 这个人现在有没有连着（用例与后台在线数用）。 */
    public boolean online(Long volunteerId) {
        Set<WebSocketSession> set = sessions.get(volunteerId);
        return set != null && !set.isEmpty();
    }

    /** 推一条新私信给收件人。失败只记日志。 */
    public void pushMessage(Long receiverId, Object payload) {
        Set<WebSocketSession> set = sessions.get(receiverId);
        if (set == null || set.isEmpty()) {
            return;
        }
        String text;
        try {
            text = objectMapper.writeValueAsString(Map.of("type", "message", "data", payload));
        } catch (Exception e) {
            log.warn("私信推送序列化失败 receiverId={}", receiverId, e);
            return;
        }
        for (WebSocketSession session : set) {
            try {
                if (session.isOpen()) {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(text));
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.warn("私信推送失败 receiverId={} sessionId={}", receiverId, session.getId(), e);
            }
        }
    }
}
