package cn.edu.sztui.stream.infrastructure.util.stream;

import cn.edu.sztui.stream.infrastructure.websocket.dto.WsMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis Stream 消息发布器（WebSocket 版）
 * <p>
 * 改造点：SseMessage → WsMessage（仅 import 和类型签名变化，逻辑不变）
 * <p>
 * 文件位置：module-stream/.../infrastructure/util/stream/StreamPublisher.java
 */
@Slf4j
@Component
public class StreamPublisher {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    public String publishSchedule(WsMessage<?> message) {
        return publish(StreamKeys.STREAM_SCHEDULE, message);
    }

    public String publishAnnouncement(WsMessage<?> message) {
        return publish(StreamKeys.STREAM_ANNOUNCEMENT, message);
    }

    public String publishCalendar(WsMessage<?> message) {
        return publish(StreamKeys.STREAM_CALENDAR, message);
    }

    public String publish(String streamKey, WsMessage<?> message) {
        try {
            Map<String, String> messageMap = buildMessageMap(message);

            StringRecord record = StreamRecords.string(messageMap).withStreamKey(streamKey);
            RecordId recordId = stringRedisTemplate.opsForStream().add(record);

            String messageId = recordId != null ? recordId.getValue() : null;
            log.info("消息发布成功 - stream: {}, messageId: {}, type: {}, targetUser: {}",
                    streamKey, messageId, message.getType(), message.getTargetUser());

            trimStream(streamKey, 1000);

            return messageId;
        } catch (Exception e) {
            log.error("消息发布失败 - stream: {}, error: {}", streamKey, e.getMessage(), e);
            throw new RuntimeException("消息发布失败", e);
        }
    }

    public void publishToUsers(String streamKey, String type, Object data, Iterable<String> targetUsers) {
        for (String targetUser : targetUsers) {
            WsMessage<Object> message = WsMessage.toUser(type, data, targetUser);
            publish(streamKey, message);
        }
    }

    public void broadcast(String streamKey, String type, Object data) {
        WsMessage<Object> message = WsMessage.broadcast(type, data);
        publish(streamKey, message);
    }

    public void publishToAll(String type, Object data) {
        broadcast(StreamKeys.STREAM_ANNOUNCEMENT, type, data);
        log.info("公告广播消息已发布 - type: {}", type);
    }

    private Map<String, String> buildMessageMap(WsMessage<?> message) {
        Map<String, String> map = new HashMap<>();
        map.put(StreamKeys.FIELD_TYPE, message.getType());
        map.put(StreamKeys.FIELD_TIMESTAMP, String.valueOf(
                message.getTimestamp() != null ? message.getTimestamp() : System.currentTimeMillis()));

        if (message.getTargetUser() != null) {
            map.put(StreamKeys.FIELD_TARGET_USER, message.getTargetUser());
        }

        if (message.getData() != null) {
            try {
                map.put(StreamKeys.FIELD_DATA, objectMapper.writeValueAsString(message.getData()));
            } catch (Exception e) {
                log.error("数据序列化失败", e);
                map.put(StreamKeys.FIELD_DATA, "{}");
            }
        }

        if (message.getMessage() != null) {
            map.put("message", message.getMessage());
        }

        return map;
    }

    private void trimStream(String streamKey, long maxLen) {
        try {
            stringRedisTemplate.opsForStream().trim(streamKey, maxLen, true);
        } catch (Exception e) {
            log.warn("Stream 修剪失败 - stream: {}", streamKey);
        }
    }
}