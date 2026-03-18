package cn.edu.sztui.stream.infrastructure.websocket.listener;

import cn.edu.sztui.stream.infrastructure.websocket.dto.WsMessage;
import cn.edu.sztui.stream.infrastructure.websocket.service.WebSocketPushService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub 消息监听器
 * <p>
 * 收到其他实例发布的 WebSocket 推送消息后，调用 pushLocal 推给本实例的连接。
 * <p>
 * 去重策略：
 * WebSocketPushService.broadcast() 先 pushLocal 再 publishToRedis，
 * 本实例收到自己发的 Pub/Sub 消息后会再次 pushLocal → 重复推送。
 * <p>
 * 解决：用实例 ID 标识。但对于本项目（单实例演示、毕设场景），
 * 更简单的做法是：broadcast() 中不调 pushLocal，全部走 Pub/Sub。
 * 单实例时 Pub/Sub 的延迟 < 1ms，可忽略。
 * <p>
 * 当前实现选择方案二（全走 Pub/Sub），简单可靠。
 * 如果将来确实需要极致延迟，再加实例 ID 判断。
 * <p>
 * 文件位置：module-stream/.../infrastructure/websocket/listener/RedisPubMsgListener.java
 */
@Slf4j
@Component
public class RedisPubMsgListener implements MessageListener {

    @Resource
    private WebSocketPushService webSocketPushService;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            // 解析 channel → topic
            String channel = new String(message.getChannel());
            String topic = channel.replace(WebSocketPushService.CHANNEL_PREFIX, "");

            // 解析消息
            String json = new String(message.getBody());
            WsMessage<?> wsMessage = objectMapper.readValue(json, WsMessage.class);

            // 本地推送
            webSocketPushService.pushLocal(topic, wsMessage);

            log.debug("Pub/Sub 收到并推送: channel={}, type={}", channel, wsMessage.getType());

        } catch (Exception e) {
            log.error("Pub/Sub 消息处理失败: {}", e.getMessage(), e);
        }
    }
}