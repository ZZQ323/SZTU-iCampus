package cn.edu.sztui.stream.application.service;

import cn.edu.sztui.stream.infrastructure.util.sse.SseEmitterManager;
import cn.edu.sztui.stream.infrastructure.util.sse.dto.SseMessage;
import cn.edu.sztui.stream.infrastructure.util.stream.StreamKeys;
import cn.edu.sztui.stream.infrastructure.util.stream.StreamPublisher;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;

/**
 * 流式推送服务
 * 
 * 封装消息推送逻辑，供其他业务服务调用
 * 
 * 文件位置：module-stream/src/main/java/cn/edu/sztui/stream/application/service/StreamPushService.java
 */
@Slf4j
@Service
public class StreamPushService {
    
    @Resource
    private StreamPublisher streamPublisher;
    
    @Resource
    private SseEmitterManager sseEmitterManager;
    
    // ==================== 课表推送 ====================
    
    /**
     * 推送课表给指定用户
     * 
     * @param wxOpenId 用户微信OpenId
     * @param schedule 课表数据
     */
    public void pushScheduleToUser(String wxOpenId, Object schedule) {
        SseMessage<Object> message = SseMessage.dataTo(
                StreamKeys.TYPE_SCHEDULE_DATA,
                schedule,
                wxOpenId
        );
        streamPublisher.publishSchedule(message);
        log.info("已发布课表推送 - user: {}", wxOpenId);
    }
    
    /**
     * 广播课表给所有订阅者
     * 
     * 注意: 通常不需要广播课表，因为每个用户的课表不同
     * 这个方法主要用于测试或特殊场景
     */
    public void broadcastSchedule(Object schedule) {
        SseMessage<Object> message = SseMessage.data(
                StreamKeys.TYPE_SCHEDULE_DATA,
                schedule
        );
        streamPublisher.publishSchedule(message);
        log.info("已广播课表 - 订阅者数量: {}", sseEmitterManager.getConnectionCount("schedule"));
    }
    
    /**
     * 推送课表刷新提醒
     * 
     * @param wxOpenId 用户微信OpenId
     * @param hint 提醒消息
     */
    public void pushScheduleRefreshHint(String wxOpenId, String hint) {
        SseMessage<Map<String, String>> message = SseMessage.dataTo(
                StreamKeys.TYPE_SCHEDULE_DATA,
                Map.of(
                        "action", "REFRESH_HINT",
                        "message", hint
                ),
                wxOpenId
        );
        streamPublisher.publishSchedule(message);
    }
    
    /**
     * 批量推送课表刷新提醒
     */
    public void pushScheduleRefreshHintToUsers(Collection<String> wxOpenIds, String hint) {
        for (String wxOpenId : wxOpenIds) {
            pushScheduleRefreshHint(wxOpenId, hint);
        }
    }
    
    /**
     * 推送认证失效提醒
     * 
     * @param wxOpenId 用户微信OpenId
     * @param message 提醒消息
     */
    public void pushAuthRequired(String wxOpenId, String message) {
        SseMessage<Void> authMsg = SseMessage.authRequired(wxOpenId, message);
        streamPublisher.publishSchedule(authMsg);
        log.info("已发布认证失效提醒 - user: {}", wxOpenId);
    }
    
    // ==================== 公告推送 ====================
    
    /**
     * 广播公告
     * 
     * @param announcement 公告数据
     */
    public void broadcastAnnouncement(Object announcement) {
        SseMessage<Object> message = SseMessage.data(
                StreamKeys.TYPE_ANNOUNCEMENT_DATA,
                announcement
        );
        streamPublisher.publishAnnouncement(message);
        log.info("已广播公告 - 订阅者数量: {}", sseEmitterManager.getConnectionCount("announcement"));
    }
    
    /**
     * 推送公告给指定用户
     */
    public void pushAnnouncementToUser(String wxOpenId, Object announcement) {
        SseMessage<Object> message = SseMessage.dataTo(
                StreamKeys.TYPE_ANNOUNCEMENT_DATA,
                announcement,
                wxOpenId
        );
        streamPublisher.publishAnnouncement(message);
    }
    
    /**
     * 广播新公告通知
     * 
     * @param data 新公告数据（包含 ids, count, metas, latestId）
     */
    public void broadcastNewAnnouncements(Object data) {
        streamPublisher.publishToAll(StreamKeys.TYPE_NEW_ANNOUNCEMENTS, data);
    }
    
    // ==================== 日历/活动推送 ====================
    
    /**
     * 广播日历事件
     */
    public void broadcastCalendarEvent(Object event) {
        SseMessage<Object> message = SseMessage.data(
                StreamKeys.TYPE_CALENDAR_DATA,
                event
        );
        streamPublisher.publishCalendar(message);
    }
    
    // ==================== 查询方法 ====================
    
    /**
     * 获取课表订阅者数量
     */
    public int getScheduleSubscriberCount() {
        return sseEmitterManager.getConnectionCount("schedule");
    }
    
    /**
     * 获取所有课表订阅者
     */
    public Collection<String> getScheduleSubscribers() {
        return sseEmitterManager.getSubscribers("schedule");
    }
    
    /**
     * 检查用户是否订阅了课表
     */
    public boolean isUserSubscribedToSchedule(String wxOpenId) {
        return sseEmitterManager.isSubscribed("schedule", wxOpenId);
    }
    
    /**
     * 获取公告订阅者数量
     */
    public int getAnnouncementSubscriberCount() {
        return sseEmitterManager.getConnectionCount("announcement");
    }
    
    /**
     * 获取总连接数
     */
    public int getTotalConnectionCount() {
        return sseEmitterManager.getTotalConnectionCount();
    }
}
