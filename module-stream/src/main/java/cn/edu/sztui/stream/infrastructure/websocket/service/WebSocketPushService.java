package cn.edu.sztui.stream.infrastructure.websocket.service;

import cn.edu.sztui.stream.infrastructure.websocket.dto.WsMessage;
import cn.edu.sztui.stream.infrastructure.websocket.registry.WsSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebSocket 推送服务
 * <p>
 * 替代 SseEmitterManager 的推送功能。
 * <p>
 * 推送策略：方案 D（虚拟线程）+ 方案 C（Redis Pub/Sub 多实例）
 * <ul>
 *   <li>本地推送：遍历 WsSessionRegistry，每个 session 一个虚拟线程</li>
 *   <li>多实例广播：PUBLISH 到 Redis channel，其他实例的 RedisPubMsgListener 收到后调本地推送</li>
 * </ul>
 * <p>
 * 文件位置：module-stream/.../infrastructure/websocket/service/WebSocketPushService.java
 */
@Slf4j
@Service
public class WebSocketPushService {

    /**
     * Redis Pub/Sub channel 前缀
     */
    public static final String CHANNEL_PREFIX = "ws:push:";

    @Resource
    private WsSessionRegistry sessionRegistry;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * JDK21 虚拟线程池：每个推送任务一个虚拟线程
     * sendMessage 是 I/O 阻塞操作，虚拟线程在此自动挂起，不占平台线程
     */
    private final ExecutorService pushExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // ==================== 对外接口（StreamConsumer 调这里） ====================

    /**
     * 广播消息到指定 topic（本地推送 + Redis Pub/Sub 多实例广播）
     * <p>
     * 这是 StreamConsumer 的替换入口：
     * 原来调 sseEmitterManager.broadcast()，现在调这个方法。
     *
     * @param topic   主题名称（announcement / schedule / calendar）
     * @param message 消息体
     */
    public void broadcast(String topic, WsMessage<?> message) {
        // 全部走 Redis Pub/Sub：本实例的 RedisPubMsgListener 收到后调 pushLocal
        // 这样单实例和多实例行为一致，不会重复推送
        // 单实例 Pub/Sub 延迟 < 1ms，可忽略
        publishToRedis(topic, message);
    }

    /**
     * 定向推送给指定用户
     *
     * @param topic   主题
     * @param userId  目标用户
     * @param message 消息体
     * @return 是否发送成功
     */
    public boolean pushToUser(String topic, String userId, WsMessage<?> message) {
        WebSocketSession session = sessionRegistry.getUserSession(userId);
        if (session == null || !session.isOpen()) {
            log.debug("用户未连接: userId={}, topic={}", userId, topic);
            return false;
        }

        return doSend(session, message);
    }

    /**
     * 根据 message 的 targetUser 决定广播还是定向
     */
    public void send(String topic, WsMessage<?> message) {
        if (message.getTargetUser() == null) {
            broadcast(topic, message);
        } else {
            pushToUser(topic, message.getTargetUser(), message);
        }
    }

    // ==================== 本地推送（仅推给本实例的连接） ====================

    /**
     * 本地推送：仅推送给当前 JVM 内注册的连接
     * <p>
     * RedisPubMsgListener 收到跨实例消息后也调这个方法。
     */
    public void pushLocal(String topic, WsMessage<?> message) {
        Set<WebSocketSession> sessions = sessionRegistry.getSessions(topic);
        if (sessions.isEmpty()) {
            return;
        }

        List<WebSocketSession> deadSessions = new ArrayList<>();

        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                deadSessions.add(session);
                continue;
            }

            // 如果是定向消息，检查目标用户
            if (message.getTargetUser() != null) {
                String userId = sessionRegistry.getUserId(session);
                if (!message.getTargetUser().equals(userId)) {
                    continue;
                }
            }

            // 虚拟线程异步推送
            pushExecutor.execute(() -> doSend(session, message));
        }

        // 清理死连接
        for (WebSocketSession dead : deadSessions) {
            sessionRegistry.unregister(dead);
        }
    }

    // ==================== Redis Pub/Sub 多实例广播 ====================

    /**
     * 发布消息到 Redis channel，供其他实例消费
     */
    private void publishToRedis(String topic, WsMessage<?> message) {
        try {
            String channel = CHANNEL_PREFIX + topic;
            String json = objectMapper.writeValueAsString(message);
            stringRedisTemplate.convertAndSend(channel, json);
            log.debug("Pub/Sub 发布: channel={}", channel);
        } catch (Exception e) {
            log.warn("Pub/Sub 发布失败: topic={}, error={}", topic, e.getMessage());
        }
    }

    // ==================== 底层发送 ====================

    /**
     * 向单个 session 发送消息
     */
    private boolean doSend(WebSocketSession session, WsMessage<?> message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            return true;
        } catch (Exception e) {
            log.warn("WS 发送失败: sessionId={}, error={}", session.getId(), e.getMessage());
            sessionRegistry.unregister(session);
            return false;
        }
    }

    // ==================== 统计 ====================

    public int getTotalConnectionCount() {
        return sessionRegistry.getTotalConnectionCount();
    }

    public int getConnectionCount(String topic) {
        return sessionRegistry.getConnectionCount(topic);
    }
}