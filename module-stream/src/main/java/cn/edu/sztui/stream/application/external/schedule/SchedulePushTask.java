package cn.edu.sztui.stream.application.external.schedule;

import cn.edu.sztui.base.application.dto.query.CrouseTableQuery;
import cn.edu.sztui.base.application.service.AcademicService;
import cn.edu.sztui.base.application.vo.CourseTableVo;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.stream.infrastructure.sse.SseEmitterManager;
import cn.edu.sztui.stream.infrastructure.sse.dto.SseMessage;
import cn.edu.sztui.stream.infrastructure.stream.StreamKeys;
import cn.edu.sztui.stream.infrastructure.stream.StreamPublisher;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 定时推送任务
 * <p>
 * 【更新】使用 getCrouseTableByOpenId 实现真正的课表推送
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
    
    /**
     * 每天早上 7:00 推送当日课表
     * <p>
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
            try {
                // 1. 检查是否已登录学校系统
                if (!authSessionCacheUtil.isSchoolLoggedIn(wxOpenId)) {
                    log.debug("用户 {} 未登录学校系统", wxOpenId);
                    SseMessage<Void> authMsg = SseMessage.authRequired(wxOpenId, "请先登录学校系统");
                    streamPublisher.publishSchedule(authMsg);
                    authFailCount++;
                    continue;
                }
                
                // 2. 检查 Cookie 是否可能过期
                if (authSessionCacheUtil.isCookiePossiblyExpired(wxOpenId)) {
                    log.debug("用户 {} Cookie 可能已过期", wxOpenId);
                    SseMessage<Void> expiredMsg = SseMessage.authRequired(wxOpenId, "会话已过期，请重新登录");
                    streamPublisher.publishSchedule(expiredMsg);
                    cookieExpiredCount++;
                    continue;
                }
                
                // 3. 使用 getCrouseTableByOpenId 获取课表
                CourseTableVo schedule = academicService.getCrouseTableByOpenId(
                        wxOpenId, 
                        new CrouseTableQuery()
                );
                
                // 4. 推送课表数据
                SseMessage<CourseTableVo> dataMsg = SseMessage.dataTo(
                        StreamKeys.TYPE_SCHEDULE_DATA,
                        schedule,
                        wxOpenId
                );
                streamPublisher.publishSchedule(dataMsg);
                
                successCount++;
                log.debug("成功推送课表给用户 {}", wxOpenId);
                
            } catch (Exception e) {
                log.error("推送课表给用户 {} 失败: {}", wxOpenId, e.getMessage());
                errorCount++;
                
                // 推送错误提示给用户
                try {
                    SseMessage<Void> errorMsg = SseMessage.authRequired(wxOpenId, "获取课表失败，请稍后重试");
                    sseEmitterManager.sendToUser("schedule", wxOpenId, errorMsg);
                } catch (Exception ignored) {
                }
            }
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
    
    /**
     * 每 10 分钟检测公告更新
     */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    public void checkAnnouncementUpdates() {
        // TODO: 实现公告更新检测逻辑
        log.debug("公告更新检测...");
    }
}
