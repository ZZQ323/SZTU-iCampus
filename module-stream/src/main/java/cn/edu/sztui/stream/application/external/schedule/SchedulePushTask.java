package cn.edu.sztui.stream.application.external.schedule;

import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.stream.infrastructure.util.sse.SseEmitterManager;
import cn.edu.sztui.stream.infrastructure.util.stream.StreamPublisher;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 定时推送任务
 * 
 * 文件位置：module-stream/src/main/java/cn/edu/sztui/stream/application/external/schedule/SchedulePushTask.java
 */
@Slf4j
@Component
@EnableScheduling
public class SchedulePushTask {
    
    @Resource
    private SseEmitterManager sseEmitterManager;
    
    @Resource
    private StreamPublisher streamPublisher;
    
    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;
    
    /**
     * 每天早上 7:00 推送当日课表
     * 
     * cron: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 0 7 * * ?")
    public void pushDailySchedule() {
        log.info("======== 开始每日课表推送任务 ========");
        doPushScheduleToSubscribers();
    }
    
    /**
     * 每 15 分钟推送一次（可选，用于测试）
     * 生产环境可以注释掉
     */
    // @Scheduled(cron = "0 */15 * * * ?")
    public void pushSchedulePeriodically() {
        log.info("======== 定时课表推送 ========");
        doPushScheduleToSubscribers();
    }
    
    /**
     * 向所有订阅者推送课表
     */
    private void doPushScheduleToSubscribers() {
        Set<String> subscribers = sseEmitterManager.getSubscribers("schedule");
        
        if (subscribers.isEmpty()) {
            log.info("无活跃订阅者，跳过推送");
            return;
        }
        
        log.info("当前活跃订阅者数量: {}", subscribers.size());
        
        int successCount = 0;
        int authFailCount = 0;
        int cookieExpiredCount = 0;
        int errorCount = 0;
        
        for (String wxOpenId : subscribers) {
            // TODO: 实现课表推送逻辑
            // 1. 检查是否已登录学校系统
            // 2. 检查 Cookie 是否可能过期
            // 3. 获取课表数据
            // 4. 推送课表数据
        }
        
        log.info("课表推送完成 - 成功: {}, 未登录: {}, Cookie过期: {}, 错误: {}", 
                successCount, authFailCount, cookieExpiredCount, errorCount);
    }
    
    /**
     * 每 5 分钟发送心跳，保持连接活跃
     */
    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void sendHeartbeat() {
        int totalConnections = sseEmitterManager.getTotalConnectionCount();
        
        if (totalConnections > 0) {
            log.debug("发送心跳 - 当前连接数: {}", totalConnections);
            
            sseEmitterManager.sendHeartbeat("schedule");
            sseEmitterManager.sendHeartbeat("announcement");
            sseEmitterManager.sendHeartbeat("calendar");
        }
    }
    
    /**
     * 每小时检测并清理过期连接
     */
    @Scheduled(fixedRate = 60 * 60 * 1000)
    public void cleanupConnections() {
        int before = sseEmitterManager.getTotalConnectionCount();
        
        sseEmitterManager.sendHeartbeat("schedule");
        sseEmitterManager.sendHeartbeat("announcement");
        sseEmitterManager.sendHeartbeat("calendar");
        
        int after = sseEmitterManager.getTotalConnectionCount();
        
        if (before != after) {
            log.info("连接清理完成 - 清理前: {}, 清理后: {}", before, after);
        }
    }
}
