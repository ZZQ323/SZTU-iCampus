package cn.edu.sztui.stream.application.service;

import cn.edu.sztui.stream.infrastructure.websocket.dto.WsMessage;
import cn.edu.sztui.stream.infrastructure.websocket.registry.WsSessionRegistry;
import cn.edu.sztui.stream.infrastructure.util.stream.StreamKeys;
import cn.edu.sztui.stream.infrastructure.util.stream.StreamPublisher;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 流式推送服务（WebSocket 版）
 * <p>
 * 改造点：
 * <ul>
 *   <li>SseEmitterManager → WsSessionRegistry（查询连接数/订阅者）</li>
 *   <li>SseMessage → WsMessage（消息格式）</li>
 * </ul>
 * <p>
 * 文件位置：module-stream/.../application/service/StreamPushService.java
 */
@Slf4j
@Service
public class StreamPushService {

    @Resource
    private StreamPublisher streamPublisher;

    @Resource
    private WsSessionRegistry wsSessionRegistry;

    // ==================== 课表推送 ====================

    public void pushScheduleToUser(String wxOpenId, Object schedule) {
        WsMessage<Object> message = WsMessage.toUser(
                StreamKeys.TYPE_SCHEDULE_DATA, schedule, wxOpenId);
        streamPublisher.publishSchedule(message);
        log.info("已发布课表推送 - user: {}", wxOpenId);
    }

    public void broadcastSchedule(Object schedule) {
        WsMessage<Object> message = WsMessage.broadcast(
                StreamKeys.TYPE_SCHEDULE_DATA, schedule);
        streamPublisher.publishSchedule(message);
        log.info("已广播课表 - 订阅者数量: {}", wsSessionRegistry.getConnectionCount("schedule"));
    }

    public void pushScheduleRefreshHint(String wxOpenId, String hint) {
        WsMessage<Map<String, String>> message = WsMessage.toUser(
                StreamKeys.TYPE_SCHEDULE_DATA,
                Map.of("action", "REFRESH_HINT", "message", hint),
                wxOpenId);
        streamPublisher.publishSchedule(message);
    }

    public void pushScheduleRefreshHintToUsers(Collection<String> wxOpenIds, String hint) {
        for (String wxOpenId : wxOpenIds) {
            pushScheduleRefreshHint(wxOpenId, hint);
        }
    }

    public void pushAuthRequired(String wxOpenId, String message) {
        WsMessage<Void> authMsg = WsMessage.toUser(
                StreamKeys.TYPE_AUTH_REQUIRED, null, wxOpenId);
        authMsg.setMessage(message);
        streamPublisher.publishSchedule(authMsg);
        log.info("已发布认证失效提醒 - user: {}", wxOpenId);
    }

    // ==================== 公告推送 ====================

    public void broadcastAnnouncement(Object announcement) {
        WsMessage<Object> message = WsMessage.broadcast(
                StreamKeys.TYPE_ANNOUNCEMENT_DATA, announcement);
        streamPublisher.publishAnnouncement(message);
        log.info("已广播公告 - 订阅者数量: {}", wsSessionRegistry.getConnectionCount("announcement"));
    }

    public void pushAnnouncementToUser(String wxOpenId, Object announcement) {
        WsMessage<Object> message = WsMessage.toUser(
                StreamKeys.TYPE_ANNOUNCEMENT_DATA, announcement, wxOpenId);
        streamPublisher.publishAnnouncement(message);
    }

    public void broadcastNewAnnouncements(Object data) {
        streamPublisher.publishToAll(StreamKeys.TYPE_NEW_ANNOUNCEMENTS, data);
    }

    // ==================== 日历/活动推送 ====================

    public void broadcastCalendarEvent(Object event) {
        WsMessage<Object> message = WsMessage.broadcast(
                StreamKeys.TYPE_CALENDAR_DATA, event);
        streamPublisher.publishCalendar(message);
    }

    // ==================== 查询方法 ====================

    public int getScheduleSubscriberCount() {
        return wsSessionRegistry.getConnectionCount("schedule");
    }

    public Set<String> getScheduleSubscribers() {
        return wsSessionRegistry.getSubscribers("schedule");
    }

    public int getAnnouncementSubscriberCount() {
        return wsSessionRegistry.getConnectionCount("announcement");
    }

    public int getTotalConnectionCount() {
        return wsSessionRegistry.getTotalConnectionCount();
    }
}