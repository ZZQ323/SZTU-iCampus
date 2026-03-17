package cn.edu.sztui.stream.infrastructure.util.sse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE 消息封装
 * 
 * 文件位置：module-stream/src/main/java/cn/edu/sztui/stream/infrastructure/util/sse/dto/SseMessage.java
 * 
 * @param <T> 数据类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SseMessage<T> {

    /** 消息类型 */
    private String type;

    /** 消息数据 */
    private T data;

    /** 目标用户（null 表示广播） */
    private String targetUser;

    /** 时间戳 */
    private Long timestamp;

    /** 提示消息（用于 AUTH_REQUIRED 等场景） */
    private String message;

    // ==================== 静态工厂方法 ====================

    /**
     * 创建数据消息（广播）
     */
    public static <T> SseMessage<T> data(String type, T data) {
        return SseMessage.<T>builder()
                .type(type)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 创建数据消息（定向）
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
     * 创建认证失效消息
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
