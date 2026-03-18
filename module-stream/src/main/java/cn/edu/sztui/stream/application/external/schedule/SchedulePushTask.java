package cn.edu.sztui.stream.application.external.schedule;

import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.stream.infrastructure.websocket.registry.WsSessionRegistry;
import cn.edu.sztui.stream.infrastructure.util.stream.StreamPublisher;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 定时推送任务（WebSocket 版）
 * <p>
 * 改造点：
 * <ul>
 *   <li>SseEmitterManager → WsSessionRegistry（获取订阅者列表）</li>
 *   <li>删除心跳任务（WebSocket 有原生 ping/pong）</li>
 *   <li>删除连接清理任务（WsSessionRegistry 在断开时自动清理）</li>
 * </ul>
 * <p>
 * 文件位置：module-stream/.../application/external/schedule/SchedulePushTask.java
 */
@Slf4j
@Component
@EnableScheduling
public class SchedulePushTask {

    @Resource
    private WsSessionRegistry wsSessionRegistry;

    @Resource
    private StreamPublisher streamPublisher;

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    /**
     * 每天早上 7:00 推送当日课表
     */
    @Scheduled(cron = "0 0 7 * * ?")
    public void pushDailySchedule() {
        log.info("======== 开始每日课表推送任务 ========");
        doPushScheduleToSubscribers();
    }

    private void doPushScheduleToSubscribers() {
        Set<String> subscribers = wsSessionRegistry.getSubscribers("schedule");

        if (subscribers.isEmpty()) {
            log.info("无活跃订阅者，跳过推送");
            return;
        }

        log.info("当前活跃订阅者数量: {}", subscribers.size());

        // TODO: 实现课表推送逻辑
        // 1. 检查是否已登录学校系统
        // 2. 获取课表数据
        // 3. 通过 StreamPublisher 发布到 Redis Stream → StreamConsumer → WebSocket 推送
    }

    // 注意：原来的 sendHeartbeat() 和 cleanupConnections() 已删除
    // WebSocket 有原生 ping/pong 保活机制，不需要应用层心跳
}