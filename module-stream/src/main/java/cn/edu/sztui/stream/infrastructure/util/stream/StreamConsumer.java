package cn.edu.sztui.stream.infrastructure.util.stream;

import cn.edu.sztui.stream.infrastructure.websocket.dto.WsMessage;
import cn.edu.sztui.stream.infrastructure.websocket.service.WebSocketPushService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Redis Stream 消息消费器（WebSocket 版）
 * <p>
 * 改造点：
 * <ul>
 *   <li>SseEmitterManager → WebSocketPushService</li>
 *   <li>SseMessage → WsMessage</li>
 * </ul>
 * <p>
 * 其余逻辑（消息解析、ACK）完全不变。
 * <p>
 * 文件位置：module-stream/.../infrastructure/util/stream/StreamConsumer.java
 */
@Slf4j
@Component
public class StreamConsumer {

    @Resource
    private WebSocketPushService webSocketPushService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 处理课表消息（由 StreamMessageListenerContainer 调用）
     */
    public void onScheduleMessage(MapRecord<String, String, String> record) {
        processMessage("schedule", record);
    }

    /**
     * 处理公告消息
     */
    public void onAnnouncementMessage(MapRecord<String, String, String> record) {
        processMessage("announcement", record);
    }

    /**
     * 处理日历消息
     */
    public void onCalendarMessage(MapRecord<String, String, String> record) {
        processMessage("calendar", record);
    }

    /**
     * 通用消息处理逻辑
     */
    private void processMessage(String topic, MapRecord<String, String, String> record) {
        String messageId = record.getId().getValue();
        Map<String, String> messageMap = record.getValue();

        log.debug("收到 Stream 消息 - topic: {}, messageId: {}", topic, messageId);

        try {
            // 解析消息字段
            String type = messageMap.get(StreamKeys.FIELD_TYPE);
            String targetUser = messageMap.get(StreamKeys.FIELD_TARGET_USER);
            String dataJson = messageMap.get(StreamKeys.FIELD_DATA);
            String timestampStr = messageMap.get(StreamKeys.FIELD_TIMESTAMP);
            String extraMessage = messageMap.get("message");

            // 构建 WsMessage（替代原来的 SseMessage）
            WsMessage<JsonNode> wsMessage = WsMessage.<JsonNode>builder()
                    .type(type)
                    .targetUser(targetUser)
                    .message(extraMessage)
                    .timestamp(timestampStr != null ? Long.parseLong(timestampStr) : System.currentTimeMillis())
                    .build();

            // 解析 data JSON
            if (dataJson != null && !dataJson.isEmpty()) {
                try {
                    JsonNode dataNode = objectMapper.readTree(dataJson);
                    wsMessage.setData(dataNode);
                } catch (Exception e) {
                    log.warn("解析消息 data 失败: {}", e.getMessage());
                }
            }

            // ★ 核心替换点：调 WebSocketPushService 替代 SseEmitterManager
            if (targetUser == null) {
                webSocketPushService.broadcast(topic, wsMessage);
                log.info("广播消息 - topic: {}, type: {}, messageId: {}", topic, type, messageId);
            } else {
                boolean sent = webSocketPushService.pushToUser(topic, targetUser, wsMessage);
                if (sent) {
                    log.info("定向推送成功 - topic: {}, type: {}, user: {}", topic, type, targetUser);
                } else {
                    log.debug("定向推送失败(用户未连接) - topic: {}, user: {}", topic, targetUser);
                }
            }

            // ACK（逻辑不变）
            acknowledgeMessage(record.getStream(), getGroupName(topic), messageId);

        } catch (Exception e) {
            log.error("处理 Stream 消息异常 - topic: {}, messageId: {}, error: {}",
                    topic, messageId, e.getMessage(), e);
            acknowledgeMessage(record.getStream(), getGroupName(topic), messageId);
        }
    }

    private void acknowledgeMessage(String streamKey, String groupName, String messageId) {
        try {
            stringRedisTemplate.opsForStream().acknowledge(streamKey, groupName, messageId);
        } catch (Exception e) {
            log.warn("消息确认失败 - stream: {}, messageId: {}", streamKey, messageId);
        }
    }

    private String getGroupName(String topic) {
        return switch (topic) {
            case "schedule" -> StreamKeys.GROUP_SCHEDULE;
            case "announcement" -> StreamKeys.GROUP_ANNOUNCEMENT;
            case "calendar" -> StreamKeys.GROUP_CALENDAR;
            default -> "group:" + topic;
        };
    }
}