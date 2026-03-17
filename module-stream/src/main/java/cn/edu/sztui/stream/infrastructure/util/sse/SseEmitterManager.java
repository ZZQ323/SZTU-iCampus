package cn.edu.sztui.stream.infrastructure.util.sse;

import cn.edu.sztui.stream.infrastructure.util.sse.dto.SseMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE Emitter 连接管理器
 * 
 * 管理所有 SSE 连接，支持按 topic 和用户进行消息推送
 * 
 * 文件位置：module-stream/src/main/java/cn/edu/sztui/stream/infrastructure/util/sse/SseEmitterManager.java
 */
@Slf4j
@Component
public class SseEmitterManager {
    
    @Resource
    private ObjectMapper objectMapper;
    
    /**
     * 连接存储结构: topic -> { wxOpenId -> SseEmitter }
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, SseEmitter>> topicEmitters 
            = new ConcurrentHashMap<>();
    
    /**
     * 记录用户最后一次消费的消息ID (用于断线重连时获取错过的消息)
     * wxOpenId -> lastMessageId
     */
    private final ConcurrentHashMap<String, String> userLastMessageId = new ConcurrentHashMap<>();
    
    // ==================== 默认配置 ====================
    
    /** 默认超时时间: 30分钟 */
    private static final long DEFAULT_TIMEOUT = 30 * 60 * 1000L;
    
    // ==================== 连接管理 ====================
    
    /**
     * 注册 SSE 连接
     * 
     * @param topic 订阅主题
     * @param wxOpenId 用户微信OpenId
     * @param timeout 超时时间(毫秒)
     * @return SseEmitter 实例
     */
    public SseEmitter subscribe(String topic, String wxOpenId, long timeout) {
        // 如果已有连接，先关闭旧连接
        unsubscribe(topic, wxOpenId);
        
        SseEmitter emitter = new SseEmitter(timeout);
        
        // 注册到管理器
        topicEmitters
                .computeIfAbsent(topic, k -> new ConcurrentHashMap<>())
                .put(wxOpenId, emitter);
        
        log.info("SSE 连接建立 - topic: {}, user: {}, 当前连接数: {}", 
                topic, wxOpenId, getConnectionCount(topic));
        
        // 设置回调处理
        emitter.onCompletion(() -> {
            log.info("SSE 连接完成 - topic: {}, user: {}", topic, wxOpenId);
            remove(topic, wxOpenId);
        });
        
        emitter.onTimeout(() -> {
            log.info("SSE 连接超时 - topic: {}, user: {}", topic, wxOpenId);
            remove(topic, wxOpenId);
        });
        
        emitter.onError(e -> {
            log.warn("SSE 连接错误 - topic: {}, user: {}, error: {}", topic, wxOpenId, e.getMessage());
            remove(topic, wxOpenId);
        });
        
        return emitter;
    }
    
    /**
     * 使用默认超时时间注册 SSE 连接
     */
    public SseEmitter subscribe(String topic, String wxOpenId) {
        return subscribe(topic, wxOpenId, DEFAULT_TIMEOUT);
    }
    
    /**
     * 取消订阅
     */
    public void unsubscribe(String topic, String wxOpenId) {
        Map<String, SseEmitter> emitters = topicEmitters.get(topic);
        if (emitters != null) {
            SseEmitter emitter = emitters.remove(wxOpenId);
            if (emitter != null) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
                log.info("SSE 连接已关闭 - topic: {}, user: {}", topic, wxOpenId);
            }
        }
    }
    
    /**
     * 移除连接 (内部使用)
     */
    private void remove(String topic, String wxOpenId) {
        Map<String, SseEmitter> emitters = topicEmitters.get(topic);
        if (emitters != null) {
            emitters.remove(wxOpenId);
        }
    }
    
    // ==================== 消息推送 ====================
    
    /**
     * 向指定 topic 的所有订阅者广播消息
     * 
     * @param topic 主题
     * @param message 消息
     */
    public void broadcast(String topic, SseMessage<?> message) {
        Map<String, SseEmitter> emitters = topicEmitters.get(topic);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        List<String> failedUsers = new ArrayList<>();

        for (Map.Entry<String, SseEmitter> entry : emitters.entrySet()) {
            try {
                entry.getValue().send(SseEmitter.event()
                        .name(message.getType())
                        .data(message));
            } catch (Exception e) {
                failedUsers.add(entry.getKey());
                log.warn("广播失败 - topic: {}, user: {}", topic, entry.getKey());
            }
        }

        // 清理失败的连接
        for (String user : failedUsers) {
            unsubscribe(topic, user);
        }
    }

    /**
     * 向所有 topic 的所有用户广播
     */
    public void broadcastAll(String type, Object data) {
        SseMessage<Object> message = SseMessage.data(type, data);
        broadcast("schedule", message);
        broadcast("announcement", message);
        broadcast("calendar", message);
    }
    
    /**
     * 向指定用户推送消息
     * 
     * @param topic 主题
     * @param wxOpenId 用户微信OpenId
     * @param message 消息
     * @return 是否发送成功
     */
    public boolean sendToUser(String topic, String wxOpenId, SseMessage<?> message) {
        Map<String, SseEmitter> emitters = topicEmitters.get(topic);
        if (emitters == null) {
            log.debug("topic {} 无订阅者", topic);
            return false;
        }
        
        SseEmitter emitter = emitters.get(wxOpenId);
        if (emitter == null) {
            log.debug("用户 {} 未订阅 topic {}", wxOpenId, topic);
            return false;
        }
        
        try {
            emitter.send(SseEmitter.event()
                    .name(topic)
                    .data(toJson(message), MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException e) {
            log.warn("推送消息失败 - topic: {}, user: {}, error: {}", topic, wxOpenId, e.getMessage());
            remove(topic, wxOpenId);
            return false;
        }
    }
    
    /**
     * 根据消息的 targetUser 字段决定广播还是定向推送
     */
    public void send(String topic, SseMessage<?> message) {
        if (message.getTargetUser() == null) {
            broadcast(topic, message);
        } else {
            sendToUser(topic, message.getTargetUser(), message);
        }
    }
    
    /**
     * 发送心跳消息 (保持连接活跃)
     */
    public void sendHeartbeat(String topic) {
        Map<String, SseEmitter> emitters = topicEmitters.get(topic);
        if (emitters == null || emitters.isEmpty()) return;
        
        List<String> failedUsers = new ArrayList<>();
        
        for (Map.Entry<String, SseEmitter> entry : emitters.entrySet()) {
            try {
                entry.getValue().send(SseEmitter.event()
                        .comment("heartbeat"));  // SSE 注释格式，不触发 onmessage
            } catch (IOException e) {
                failedUsers.add(entry.getKey());
            }
        }
        
        // 清理失败的连接
        for (String user : failedUsers) {
            remove(topic, user);
        }
    }
    
    // ==================== 查询方法 ====================
    
    /**
     * 获取指定 topic 的所有订阅者
     */
    public Set<String> getSubscribers(String topic) {
        Map<String, SseEmitter> emitters = topicEmitters.get(topic);
        if (emitters == null) {
            return Set.of();
        }
        return emitters.keySet();
    }
    
    /**
     * 检查用户是否订阅了指定 topic
     */
    public boolean isSubscribed(String topic, String wxOpenId) {
        Map<String, SseEmitter> emitters = topicEmitters.get(topic);
        return emitters != null && emitters.containsKey(wxOpenId);
    }
    
    /**
     * 获取指定 topic 的连接数
     */
    public int getConnectionCount(String topic) {
        Map<String, SseEmitter> emitters = topicEmitters.get(topic);
        return emitters == null ? 0 : emitters.size();
    }
    
    /**
     * 获取所有 topic 的总连接数
     */
    public int getTotalConnectionCount() {
        return topicEmitters.values().stream()
                .mapToInt(Map::size)
                .sum();
    }
    
    // ==================== 断线重连支持 ====================
    
    /**
     * 记录用户最后消费的消息ID
     */
    public void updateLastMessageId(String wxOpenId, String messageId) {
        userLastMessageId.put(wxOpenId, messageId);
    }
    
    /**
     * 获取用户最后消费的消息ID
     */
    public String getLastMessageId(String wxOpenId) {
        return userLastMessageId.get(wxOpenId);
    }
    
    // ==================== 工具方法 ====================
    
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("JSON 序列化失败", e);
            return "{}";
        }
    }
}
