package cn.edu.sztui.stream.infrastructure.util.stream;

import cn.edu.sztui.stream.infrastructure.util.sse.SseEmitterManager;
import cn.edu.sztui.stream.infrastructure.util.sse.dto.SseMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Redis Stream 消息消费器
 * 
 * 从 Redis Stream 消费消息，并通过 SSE 推送给客户端
 * 
 * 文件位置：module-stream/src/main/java/cn/edu/sztui/stream/infrastructure/util/stream/StreamConsumer.java
 */
@Slf4j
@Component
public class StreamConsumer {
    
    @Resource
    private SseEmitterManager sseEmitterManager;
    
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    
    @Resource
    private ObjectMapper objectMapper;
    
    /**
     * 处理课表消息
     * 
     * 由 StreamMessageListenerContainer 调用
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
     * 
     * @param topic SSE topic 名称
     * @param record Redis Stream 消息记录
     */
    private void processMessage(String topic, MapRecord<String, String, String> record) {
        String messageId = record.getId().getValue();
        Map<String, String> messageMap = record.getValue();
        
        log.debug("收到 Stream 消息 - topic: {}, messageId: {}", topic, messageId);
        
        try {
            // 解析消息
            String type = messageMap.get(StreamKeys.FIELD_TYPE);
            String targetUser = messageMap.get(StreamKeys.FIELD_TARGET_USER);
            String dataJson = messageMap.get(StreamKeys.FIELD_DATA);
            String timestampStr = messageMap.get(StreamKeys.FIELD_TIMESTAMP);
            String extraMessage = messageMap.get("message");
            
            // 构建 SSE 消息
            SseMessage<JsonNode> sseMessage = SseMessage.<JsonNode>builder()
                    .type(type)
                    .targetUser(targetUser)
                    .message(extraMessage)
                    .timestamp(timestampStr != null ? Long.parseLong(timestampStr) : System.currentTimeMillis())
                    .build();
            
            // 解析 data JSON
            if (dataJson != null && !dataJson.isEmpty()) {
                try {
                    JsonNode dataNode = objectMapper.readTree(dataJson);
                    sseMessage.setData(dataNode);
                } catch (Exception e) {
                    log.warn("解析消息 data 失败: {}", e.getMessage());
                }
            }
            
            // 推送消息
            if (targetUser == null) {
                // 广播
                sseEmitterManager.broadcast(topic, sseMessage);
                log.info("广播消息 - topic: {}, type: {}, messageId: {}", topic, type, messageId);
            } else {
                // 定向推送
                boolean sent = sseEmitterManager.sendToUser(topic, targetUser, sseMessage);
                if (sent) {
                    log.info("定向推送成功 - topic: {}, type: {}, user: {}, messageId: {}", 
                            topic, type, targetUser, messageId);
                } else {
                    log.debug("定向推送失败(用户未连接) - topic: {}, user: {}", topic, targetUser);
                }
            }
            
            // 确认消息 (ACK)
            acknowledgeMessage(record.getStream(), getGroupName(topic), messageId);
            
        } catch (Exception e) {
            log.error("处理 Stream 消息异常 - topic: {}, messageId: {}, error: {}", 
                    topic, messageId, e.getMessage(), e);
            // 即使处理失败也确认消息，避免重复消费
            acknowledgeMessage(record.getStream(), getGroupName(topic), messageId);
        }
    }
    
    /**
     * 确认消息已处理
     */
    private void acknowledgeMessage(String streamKey, String groupName, String messageId) {
        try {
            stringRedisTemplate.opsForStream().acknowledge(streamKey, groupName, messageId);
            log.debug("消息已确认 - stream: {}, messageId: {}", streamKey, messageId);
        } catch (Exception e) {
            log.warn("消息确认失败 - stream: {}, messageId: {}, error: {}", 
                    streamKey, messageId, e.getMessage());
        }
    }
    
    /**
     * 根据 topic 获取消费者组名称
     */
    private String getGroupName(String topic) {
        switch (topic) {
            case "schedule":
                return StreamKeys.GROUP_SCHEDULE;
            case "announcement":
                return StreamKeys.GROUP_ANNOUNCEMENT;
            case "calendar":
                return StreamKeys.GROUP_CALENDAR;
            default:
                return "group:" + topic;
        }
    }
}
