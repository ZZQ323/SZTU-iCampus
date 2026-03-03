package cn.edu.sztui.stream.application.external;

import cn.edu.sztui.stream.infrastructure.sse.SseEmitterManager;
import cn.edu.sztui.stream.infrastructure.sse.dto.SseMessage;
import cn.edu.sztui.stream.infrastructure.stream.StreamKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * SSE 心跳保活任务
 *
 * 每 15 秒向所有连接发送心跳，防止超时断开
 */
@Slf4j
@Component
public class SseHeartbeatTask {

    @Resource
    private SseEmitterManager sseEmitterManager;

    /**
     * 每 15 秒发送心跳
     */
    @Scheduled(fixedRate = 15000)
    public void sendHeartbeat() {
        int totalConnections = sseEmitterManager.getTotalConnectionCount();

        if (totalConnections == 0) {
            return; // 无连接时不发送
        }

        Map<String, Object> data = new HashMap<>();
        data.put("timestamp", System.currentTimeMillis());
        data.put("connections", totalConnections);

        SseMessage<Map<String, Object>> heartbeat = SseMessage.data(
                StreamKeys.TYPE_HEARTBEAT,
                data
        );

        // 向所有 topic 广播心跳
        sseEmitterManager.broadcast("schedule", heartbeat);
        sseEmitterManager.broadcast("announcement", heartbeat);
        sseEmitterManager.broadcast("calendar", heartbeat);

        log.debug("SSE 心跳已发送，当前连接数: {}", totalConnections);
    }
}