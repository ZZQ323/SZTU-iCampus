package cn.edu.sztui.stream.infrastructure.websocket.handler;

import cn.edu.sztui.stream.infrastructure.util.stream.StreamKeys;
import cn.edu.sztui.stream.infrastructure.websocket.dto.WsMessage;
import cn.edu.sztui.stream.infrastructure.websocket.interceptor.WsAuthInterceptor;
import cn.edu.sztui.stream.infrastructure.websocket.registry.WsSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 统一 WebSocket 消息处理器
 * <p>
 * 一个 Handler 处理所有 topic，通过握手阶段存入的 attributes 区分 topic。
 * 不需要为 announcement / schedule / calendar 各写一个 Handler。
 * <p>
 * 职责：
 * <ul>
 *   <li>连接建立时：从 attributes 读取 openId + topics，注册到 WsSessionRegistry</li>
 *   <li>收到消息时：目前仅处理 HEARTBEAT（前端 ping），后续可扩展</li>
 *   <li>连接关闭时：从 WsSessionRegistry 注销</li>
 * </ul>
 * <p>
 * 文件位置：module-stream/.../infrastructure/websocket/handler/UnifiedWsHandler.java
 */
@Slf4j
@Component
public class UnifiedWsHandler extends TextWebSocketHandler {

    @Resource
    private WsSessionRegistry sessionRegistry;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String openId = (String) session.getAttributes().get(WsAuthInterceptor.ATTR_OPEN_ID);
        String topicsStr = (String) session.getAttributes().get(WsAuthInterceptor.ATTR_TOPICS);

        if (openId == null || topicsStr == null) {
            log.warn("WS 连接缺少鉴权信息，关闭: sessionId={}", session.getId());
            closeQuietly(session);
            return;
        }

        // 注册到每个订阅的 topic
        String[] topics = topicsStr.split(",");
        for (String topic : topics) {
            String trimmed = topic.trim();
            if (!trimmed.isEmpty()) {
                sessionRegistry.register(trimmed, openId, session);
            }
        }

        // 发送连接成功消息
        sendConnectedMessage(session, openId, topicsStr);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();

        // 前端发来的心跳 pong 或 ping
        if ("ping".equalsIgnoreCase(payload) || "pong".equalsIgnoreCase(payload)) {
            sendQuietly(session, "pong");
            return;
        }

        // 其他消息暂不处理，预留扩展
        log.debug("收到客户端消息: sessionId={}, payload={}", session.getId(), payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String openId = (String) session.getAttributes().get(WsAuthInterceptor.ATTR_OPEN_ID);
        sessionRegistry.unregister(session);
        log.info("WS 连接关闭: openId={}, status={}", openId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String openId = (String) session.getAttributes().get(WsAuthInterceptor.ATTR_OPEN_ID);
        log.warn("WS 传输错误: openId={}, error={}", openId, exception.getMessage());
        sessionRegistry.unregister(session);
    }

    // ==================== 内部方法 ====================

    private void sendConnectedMessage(WebSocketSession session, String openId, String topics) {
        try {
            WsMessage<Object> msg = WsMessage.system(StreamKeys.TYPE_CONNECTED,
                    "连接成功，已订阅: " + topics);
            String json = objectMapper.writeValueAsString(msg);
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("发送连接成功消息失败: openId={}", openId);
        }
    }

    private void sendQuietly(WebSocketSession session, String text) {
        try {
            session.sendMessage(new TextMessage(text));
        } catch (Exception ignored) {
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }
}