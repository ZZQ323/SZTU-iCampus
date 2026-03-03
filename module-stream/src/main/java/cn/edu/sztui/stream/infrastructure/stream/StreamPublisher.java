package cn.edu.sztui.stream.infrastructure.stream;

import cn.edu.sztui.stream.infrastructure.sse.dto.SseMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis Stream 消息发布器
 * 
 * 负责将消息发布到 Redis Stream
 */
@Slf4j
@Component
public class StreamPublisher {
    
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    
    @Resource
    private ObjectMapper objectMapper;
    
    /**
     * 发布课表更新消息
     * 
     * @param message 消息内容
     * @return 消息ID
     */
    public String publishSchedule(SseMessage<?> message) {
        return publish(StreamKeys.STREAM_SCHEDULE, message);
    }
    
    /**
     * 发布公告更新消息
     * 
     * @param message 消息内容
     * @return 消息ID
     */
    public String publishAnnouncement(SseMessage<?> message) {
        return publish(StreamKeys.STREAM_ANNOUNCEMENT, message);
    }
    
    /**
     * 发布日历/活动更新消息
     * 
     * @param message 消息内容
     * @return 消息ID
     */
    public String publishCalendar(SseMessage<?> message) {
        return publish(StreamKeys.STREAM_CALENDAR, message);
    }
    
    /**
     * 通用发布方法
     * 
     * @param streamKey Stream Key
     * @param message 消息内容
     * @return 消息ID (Redis 生成的格式如 "1234567890123-0")
     */
    public String publish(String streamKey, SseMessage<?> message) {
        try {
            Map<String, String> messageMap = buildMessageMap(message);
            
            StringRecord record = StreamRecords.string(messageMap).withStreamKey(streamKey);
            RecordId recordId = stringRedisTemplate.opsForStream().add(record);
            
            String messageId = recordId != null ? recordId.getValue() : null;
            log.info("消息发布成功 - stream: {}, messageId: {}, type: {}, targetUser: {}", 
                    streamKey, messageId, message.getType(), message.getTargetUser());
            
            // 可选: 限制 Stream 长度，防止无限增长
            // 保留最近 1000 条消息
            trimStream(streamKey, 1000);
            
            return messageId;
        } catch (Exception e) {
            log.error("消息发布失败 - stream: {}, error: {}", streamKey, e.getMessage(), e);
            throw new RuntimeException("消息发布失败", e);
        }
    }
    
    /**
     * 批量发布消息 (向多个用户推送)
     * 
     * @param streamKey Stream Key
     * @param type 消息类型
     * @param data 消息数据
     * @param targetUsers 目标用户列表
     */
    public void publishToUsers(String streamKey, String type, Object data, Iterable<String> targetUsers) {
        for (String targetUser : targetUsers) {
            SseMessage<Object> message = SseMessage.dataTo(type, data, targetUser);
            publish(streamKey, message);
        }
    }
    
    /**
     * 广播消息 (发送给所有订阅者)
     * 
     * @param streamKey Stream Key
     * @param type 消息类型
     * @param data 消息数据
     */
    public void broadcast(String streamKey, String type, Object data) {
        SseMessage<Object> message = SseMessage.data(type, data);
        publish(streamKey, message);
    }
    
    /**
     * 构建消息 Map
     */
    private Map<String, String> buildMessageMap(SseMessage<?> message) {
        Map<String, String> map = new HashMap<>();
        map.put(StreamKeys.FIELD_TYPE, message.getType());
        map.put(StreamKeys.FIELD_TIMESTAMP, String.valueOf(
                message.getTimestamp() != null ? message.getTimestamp() : System.currentTimeMillis()));
        
        // 目标用户 (null 表示广播)
        if (message.getTargetUser() != null) {
            map.put(StreamKeys.FIELD_TARGET_USER, message.getTargetUser());
        }
        
        // 数据序列化为 JSON
        if (message.getData() != null) {
            try {
                map.put(StreamKeys.FIELD_DATA, objectMapper.writeValueAsString(message.getData()));
            } catch (Exception e) {
                log.error("数据序列化失败", e);
                map.put(StreamKeys.FIELD_DATA, "{}");
            }
        }
        
        // 附加消息
        if (message.getMessage() != null) {
            map.put("message", message.getMessage());
        }
        
        return map;
    }
    
    /**
     * 修剪 Stream 长度
     * 
     * 使用 XTRIM 命令限制 Stream 长度，防止内存无限增长
     * 
     * @param streamKey Stream Key
     * @param maxLen 最大长度
     */
    private void trimStream(String streamKey, long maxLen) {
        try {
            stringRedisTemplate.opsForStream().trim(streamKey, maxLen, true);
        } catch (Exception e) {
            log.warn("Stream 修剪失败 - stream: {}, error: {}", streamKey, e.getMessage());
        }
    }

    /**
     * ⭐ 新增：向所有公告订阅者广播消息
     *
     * @param type 消息类型（如 TYPE_NEW_ANNOUNCEMENTS）
     * @param data 消息数据
     */
    public void publishToAll(String type, Object data) {
        broadcast(StreamKeys.STREAM_ANNOUNCEMENT, type, data);
        log.info("公告广播消息已发布 - type: {}", type);
    }
}
