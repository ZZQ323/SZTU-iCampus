package cn.edu.sztui.stream.infrastructure.scheduler;

import cn.edu.sztui.base.application.service.AcademicService;
import cn.edu.sztui.base.infrastructure.sse.SseEmitterManager;
import cn.edu.sztui.base.infrastructure.sse.dto.SseMessage;
import cn.edu.sztui.base.infrastructure.stream.StreamKeys;
import cn.edu.sztui.base.infrastructure.stream.StreamPublisher;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * 定时推送任务
 * 
 * 负责定时推送课表、检测公告更新等
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
    private AcademicService academicService;
    
    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;
    
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    
    /**
     * 每天早上 7:00 推送当日课表
     * 
     * cron: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 0 7 * * ?")
    public void pushDailySchedule() {
        log.info("======== 开始每日课表推送任务 ========");
        
        // 获取所有订阅了 schedule 的用户
        Set<String> subscribers = sseEmitterManager.getSubscribers("schedule");
        
        if (subscribers.isEmpty()) {
            log.info("无活跃订阅者，跳过推送");
            return;
        }
        
        log.info("当前活跃订阅者数量: {}", subscribers.size());
        
        int successCount = 0;
        int authFailCount = 0;
        int errorCount = 0;
        
        for (String wxOpenId : subscribers) {
            try {
                // 检查用户 Cookie 是否有效
                if (!authSessionCacheUtil.hasSession(wxOpenId)) {
                    // Cookie 失效，发布认证提醒消息
                    SseMessage<Void> authMsg = SseMessage.authRequired(wxOpenId, "登录已过期，请重新登录以获取课表");
                    streamPublisher.publishSchedule(authMsg);
                    authFailCount++;
                    continue;
                }
                
                // 获取用户课表 (这里需要模拟用户上下文，实际实现可能需要调整)
                // 注意: 定时任务中没有用户上下文，需要特殊处理
                // 方案1: 存储用户的查询参数
                // 方案2: 使用系统账号获取通用课表
                // 方案3: 只推送提醒，让用户自己刷新
                
                // 这里采用方案3: 推送提醒消息
                SseMessage<Object> reminderMsg = SseMessage.dataTo(
                        StreamKeys.TYPE_SCHEDULE_DATA,
                        new Object() {
                            public String action = "REFRESH_HINT";
                            public String message = "早上好！记得查看今日课表哦~";
                            public String time = LocalDateTime.now().format(TIME_FORMATTER);
                        },
                        wxOpenId
                );
                
                streamPublisher.publishSchedule(reminderMsg);
                successCount++;
                
            } catch (Exception e) {
                log.error("推送课表给用户 {} 失败: {}", wxOpenId, e.getMessage());
                errorCount++;
            }
        }
        
        log.info("每日课表推送完成 - 成功: {}, 认证失败: {}, 错误: {}", 
                successCount, authFailCount, errorCount);
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
        
        // 发送一个空消息来触发连接检测
        // 失效的连接会在发送时自动被清理
        sseEmitterManager.sendHeartbeat("schedule");
        sseEmitterManager.sendHeartbeat("announcement");
        sseEmitterManager.sendHeartbeat("calendar");
        
        int after = sseEmitterManager.getTotalConnectionCount();
        
        if (before != after) {
            log.info("连接清理完成 - 清理前: {}, 清理后: {}", before, after);
        }
    }
    
    /**
     * 每 10 分钟检测公告更新 (示例)
     * 
     * 实际实现需要对接公告爬虫服务
     */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    public void checkAnnouncementUpdates() {
        // TODO: 实现公告更新检测逻辑
        // 1. 爬取最新公告
        // 2. 与缓存中的公告对比
        // 3. 如果有新公告，通过 streamPublisher.publishAnnouncement() 发布
        
        log.debug("公告更新检测...");
    }
}
