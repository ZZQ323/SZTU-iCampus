package cn.edu.sztui.stream.infrastructure.sse.dto;

import cn.edu.sztui.stream.infrastructure.stream.StreamKeys;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * SSE 消息封装
 * 
 * 用于统一 SSE 推送的消息格式
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SseMessage<T> implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 消息类型
     * @see StreamKeys
     */
    private String type;
    
    /**
     * 消息数据
     */
    private T data;
    
    /**
     * 时间戳
     */
    private Long timestamp;
    
    /**
     * 目标用户 (null 表示广播给所有订阅者)
     */
    private String targetUser;
    
    /**
     * 附加信息/提示
     */
    private String message;
    
    // ==================== 静态工厂方法 ====================
    
    /**
     * 创建数据消息
     */
    public static <T> SseMessage<T> data(String type, T data) {
        return SseMessage.<T>builder()
                .type(type)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    /**
     * 创建定向数据消息
     */
    public static <T> SseMessage<T> dataTo(String type, T data, String targetUser) {
        return SseMessage.<T>builder()
                .type(type)
                .data(data)
                .targetUser(targetUser)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    /**
     * 创建需要认证的消息
     */
    public static SseMessage<Void> authRequired(String targetUser, String message) {
        return SseMessage.<Void>builder()
                .type("AUTH_REQUIRED")
                .targetUser(targetUser)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    /**
     * 创建心跳消息
     */
    public static SseMessage<Void> heartbeat() {
        return SseMessage.<Void>builder()
                .type("HEARTBEAT")
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
