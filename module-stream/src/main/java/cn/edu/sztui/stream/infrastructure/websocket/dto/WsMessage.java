package cn.edu.sztui.stream.infrastructure.websocket.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket 统一消息格式
 * <p>
 * 替代旧的 SseMessage，结构完全一致，仅改包路径。
 * <p>
 * 格式示例：
 * <pre>{@code
 * {
 *   "type": "NEW_ANNOUNCEMENTS",
 *   "data": { "count": 3, "latestTitle": "关于..." },
 *   "timestamp": 1710000000000,
 *   "targetUser": null,
 *   "message": null
 * }
 * }</pre>
 * <p>
 * targetUser 为 null 表示广播，非 null 表示定向推送。
 * <p>
 * 文件位置：module-stream/.../infrastructure/websocket/dto/WsMessage.java
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsMessage<T> {

    /**
     * 消息类型，对应 StreamKeys.TYPE_* 常量
     */
    private String type;

    /**
     * 业务数据
     */
    private T data;

    /**
     * 时间戳
     */
    private Long timestamp;

    /**
     * 目标用户 openId，null = 广播
     */
    private String targetUser;

    /**
     * 附加文本消息
     */
    private String message;

    // ==================== 工厂方法 ====================

    /**
     * 广播消息
     */
    public static <T> WsMessage<T> broadcast(String type, T data) {
        return WsMessage.<T>builder()
                .type(type)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 定向推送
     */
    public static <T> WsMessage<T> toUser(String type, T data, String targetUser) {
        return WsMessage.<T>builder()
                .type(type)
                .data(data)
                .targetUser(targetUser)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 系统消息（无 data）
     */
    public static WsMessage<Void> system(String type, String message) {
        return WsMessage.<Void>builder()
                .type(type)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}