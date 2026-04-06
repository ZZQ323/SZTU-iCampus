package cn.edu.sztui.stream.infrastructure.websocket.registry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 会话注册表
 * <p>
 * 替代 SseEmitterManager 的连接管理部分。
 * <p>
 * 存储结构：
 * <ul>
 *   <li>topicSessions: topic → Set&lt;WebSocketSession&gt;</li>
 *   <li>sessionUsers:  sessionId → userId</li>
 *   <li>userSessions:  userId → WebSocketSession</li>
 * </ul>
 * <p>
 * 文件位置：module-stream/.../infrastructure/websocket/registry/WsSessionRegistry.java
 */
@Slf4j
@Component
public class WsSessionRegistry {

    /**
     * topic → 该 topic 下的所有 session
     */
    private final ConcurrentHashMap<String, Set<WebSocketSession>> topicSessions = new ConcurrentHashMap<>();

    /**
     * sessionId → userId
     */
    private final ConcurrentHashMap<String, String> sessionUsers = new ConcurrentHashMap<>();

    /**
     * userId → session（一个用户同时只保留一个连接）
     */
    private final ConcurrentHashMap<String, WebSocketSession> userSessions = new ConcurrentHashMap<>();

    // ==================== 注册 / 注销 ====================

    /**
     * 注册连接到指定 topic
     *
     * @param topic   订阅主题
     * @param userId  用户 userId
     * @param session WebSocket 会话
     */
    public void register(String topic, String userId, WebSocketSession session) {
        // 如果该用户已有旧连接，先关闭
        WebSocketSession old = userSessions.get(userId);
        if (old != null && old.isOpen() && !old.getId().equals(session.getId())) {
            try {
                old.close();
            } catch (Exception ignored) {
            }
            log.info("关闭用户旧连接: userId={}, oldSessionId={}", userId, old.getId());
        }

        // 注册
        topicSessions
                .computeIfAbsent(topic, k -> ConcurrentHashMap.newKeySet())
                .add(session);
        sessionUsers.put(session.getId(), userId);
        userSessions.put(userId, session);

        log.info("WS 注册: topic={}, userId={}, sessionId={}, 当前连接数={}",
                topic, userId, session.getId(), getConnectionCount(topic));
    }

    /**
     * 注销连接（从所有 topic 中移除）
     */
    public void unregister(WebSocketSession session) {
        String sessionId = session.getId();
        String userId = sessionUsers.remove(sessionId);

        // 从所有 topic 中移除
        topicSessions.values().forEach(sessions -> sessions.remove(session));

        // 从 userSessions 移除（仅当是同一个 session 时）
        if (userId != null) {
            userSessions.remove(userId, session);
        }

        log.info("WS 注销: userId={}, sessionId={}", userId, sessionId);
    }

    // ==================== 查询 ====================

    /**
     * 获取某 topic 下的所有存活 session
     */
    public Set<WebSocketSession> getSessions(String topic) {
        return topicSessions.getOrDefault(topic, Collections.emptySet());
    }

    /**
     * 获取指定用户的 session
     */
    public WebSocketSession getUserSession(String userId) {
        return userSessions.get(userId);
    }

    /**
     * 根据 session 获取 userId
     */
    public String getUserId(WebSocketSession session) {
        return sessionUsers.get(session.getId());
    }

    /**
     * 获取某 topic 的连接数
     */
    public int getConnectionCount(String topic) {
        Set<WebSocketSession> sessions = topicSessions.get(topic);
        return sessions == null ? 0 : sessions.size();
    }

    /**
     * 获取所有 topic 的总连接数
     */
    public int getTotalConnectionCount() {
        return topicSessions.values().stream()
                .mapToInt(Set::size)
                .sum();
    }

    /**
     * 获取某 topic 下所有订阅者的 userId
     */
    public Set<String> getSubscribers(String topic) {
        Set<WebSocketSession> sessions = topicSessions.get(topic);
        if (sessions == null || sessions.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> userIds = ConcurrentHashMap.newKeySet();
        for (WebSocketSession s : sessions) {
            String userId = sessionUsers.get(s.getId());
            if (userId != null) {
                userIds.add(userId);
            }
        }
        return userIds;
    }
}