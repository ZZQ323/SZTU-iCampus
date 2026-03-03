package cn.edu.sztui.stream.web;

import cn.edu.sztui.base.application.dto.query.CrouseTableQuery;
import cn.edu.sztui.base.application.service.AcademicService;
import cn.edu.sztui.base.application.vo.CourseTableVo;
import cn.edu.sztui.base.infrastructure.util.cache.AnnouncementCacheUtil;
import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.bean.TokenMessage;
import cn.edu.sztui.stream.infrastructure.cookie.CookieSourceManager;
import cn.edu.sztui.stream.infrastructure.sse.SseEmitterManager;
import cn.edu.sztui.stream.infrastructure.sse.dto.SseMessage;
import cn.edu.sztui.stream.infrastructure.stream.StreamKeys;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * SSE 订阅控制器
 */
@Slf4j
@RestController
@RequestMapping("/stream")
public class SseController {

    @Resource
    private SseEmitterManager sseEmitterManager;

    @Resource
    private AcademicService academicService;

    @Resource
    private AnnouncementCacheUtil announcementCacheUtil;

    @Resource
    private CookieSourceManager cookieSourceManager;

    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;

    /**
     * 订阅课表更新流
     */
    @GetMapping(value = "/schedule", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeSchedule() {
        TokenMessage token = UserContext.getContext();
        String wxOpenId = token.getOpenId();

        log.info("用户 {} 订阅课表流", wxOpenId);

        // 注册 SSE 连接
        SseEmitter emitter = sseEmitterManager.subscribe("schedule", wxOpenId, SSE_TIMEOUT);

        // 【关键】在主线程获取 wxOpenId，传递给异步线程
        // 异步线程无法使用 UserContext（ThreadLocal 不会传递）
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100);

                // 【修复】使用 getCrouseTableByOpenId，直接传入 wxOpenId
                CourseTableVo currentSchedule = academicService.getCrouseTableByOpenId(
                        wxOpenId,
                        new CrouseTableQuery()
                );

                SseMessage<CourseTableVo> message = SseMessage.data(
                        StreamKeys.TYPE_SCHEDULE_DATA,
                        currentSchedule
                );

                sseEmitterManager.sendToUser("schedule", wxOpenId, message);
                log.info("已推送初始课表给用户 {}", wxOpenId);

            } catch (Exception e) {
                log.error("推送初始课表失败 - user: {}, error: {}", wxOpenId, e.getMessage());

                // 认证问题，推送提醒
                if (e.getMessage() != null &&
                        (e.getMessage().contains("登录") || e.getMessage().contains("过期"))) {
                    SseMessage<Void> authMsg = SseMessage.authRequired(wxOpenId, "请重新登录以获取课表");
                    sseEmitterManager.sendToUser("schedule", wxOpenId, authMsg);
                }
            }
        });

        return emitter;
    }

    /**
     * 订阅公告更新流
     * <p>
     * 订阅后立即推送当前公告系统状态（latestId 等）
     */
    @GetMapping(value = "/announcement", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeAnnouncement() {
        TokenMessage token = UserContext.getContext();
        String wxOpenId = token.getOpenId();

        log.info("用户 {} 订阅公告流", wxOpenId);

        // 注册 SSE 连接
        SseEmitter emitter = sseEmitterManager.subscribe("announcement", wxOpenId, SSE_TIMEOUT);

        // 异步推送初始状态
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100);

                // 构建初始状态数据
                Map<String, Object> statusData = new HashMap<>();
                statusData.put("latestId", announcementCacheUtil.getLatestId());
                statusData.put("totalCount", announcementCacheUtil.getTotalCount());
                statusData.put("operational", cookieSourceManager.isSystemOperational());
                statusData.put("lastCrawlTime", announcementCacheUtil.getLastCrawlTime());

                SseMessage<Map<String, Object>> message = SseMessage.data(
                        StreamKeys.TYPE_ANNOUNCEMENT_STATUS,
                        statusData
                );

                sseEmitterManager.sendToUser("announcement", wxOpenId, message);
                log.info("已推送公告初始状态给用户 {}", wxOpenId);

            } catch (Exception e) {
                log.error("推送公告初始状态失败 - user: {}, error: {}", wxOpenId, e.getMessage());
            }
        });

        return emitter;
    }

    /**
     * 订阅日历/活动更新流
     */
    @GetMapping(value = "/calendar", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeCalendar() {
        TokenMessage token = UserContext.getContext();
        String wxOpenId = token.getOpenId();

        log.info("用户 {} 订阅日历流", wxOpenId);
        return sseEmitterManager.subscribe("calendar", wxOpenId, SSE_TIMEOUT);
    }

    /**
     * 取消订阅
     */
    @DeleteMapping("/unsubscribe/{topic}")
    public void unsubscribe(@PathVariable String topic) {
        TokenMessage token = UserContext.getContext();
        String wxOpenId = token.getOpenId();

        sseEmitterManager.unsubscribe(topic, wxOpenId);
        log.info("用户 {} 取消订阅 {}", wxOpenId, topic);
    }

    /**
     * 获取连接状态 (调试用)
     */
    @GetMapping("/status")
    public Object getStatus() {
        return new Object() {
            public int scheduleConnections = sseEmitterManager.getConnectionCount("schedule");
            public int announcementConnections = sseEmitterManager.getConnectionCount("announcement");
            public int calendarConnections = sseEmitterManager.getConnectionCount("calendar");
            public int totalConnections = sseEmitterManager.getTotalConnectionCount();
        };
    }
}