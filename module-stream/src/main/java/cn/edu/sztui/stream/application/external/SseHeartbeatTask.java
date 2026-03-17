package cn.edu.sztui.stream.application.external;

import cn.edu.sztui.stream.infrastructure.util.sse.SseEmitterManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SSE 心跳任务
 * 
 * 定时发送心跳保持连接活跃
 * 
 * 文件位置：module-stream/src/main/java/cn/edu/sztui/stream/application/external/SseHeartbeatTask.java
 */
@Slf4j
@Component
public class SseHeartbeatTask {

    @Resource
    private SseEmitterManager sseEmitterManager;

    /**
     * 每 30 秒发送心跳
     */
    @Scheduled(fixedRate = 30000)
    public void sendHeartbeat() {
        int totalConnections = sseEmitterManager.getTotalConnectionCount();

        if (totalConnections > 0) {
            sseEmitterManager.sendHeartbeat("schedule");
            sseEmitterManager.sendHeartbeat("announcement");
            sseEmitterManager.sendHeartbeat("calendar");

            log.debug("SSE 心跳已发送，当前连接数: {}", totalConnections);
        }
    }
}
