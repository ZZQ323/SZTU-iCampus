package cn.edu.sztui.stream.web;

import cn.edu.sztui.base.application.dto.query.CrouseTableQuery;
import cn.edu.sztui.base.application.service.AcademicService;
import cn.edu.sztui.base.application.vo.CourseTableVo;
import cn.edu.sztui.base.infrastructure.util.cache.AnnouncementCacheUtil;
import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.bean.TokenMessage;
import cn.edu.sztui.stream.infrastructure.sse.SseEmitterManager;
import cn.edu.sztui.stream.infrastructure.sse.dto.SseMessage;
import cn.edu.sztui.stream.infrastructure.stream.StreamKeys;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * SSE 订阅控制器
 * <p>
 * 文件：module-stream/src/main/java/cn/edu/sztui/stream/web/SseController.java
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

    // ⭐ 删除 CookieSourceManager 引用

    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;

    /**
     * 订阅课表更新流
     */
    @GetMapping(value = "/schedule", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeSchedule() {
        TokenMessage token = UserContext.getContext();
        String wxOpenId = token.getOpenId();

        log.info("用户 {} 订阅课表流", wxOpenId);

        SseEmitter emitter = sseEmitterManager.subscribe("schedule", wxOpenId, SSE_TIMEOUT);

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100);

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
     */
    @GetMapping(value = "/announcement", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeAnnouncement() {
        TokenMessage token = UserContext.getContext();
        String wxOpenId = token.getOpenId();

        log.info("用户 {} 订阅公告流", wxOpenId);

        SseEmitter emitter = sseEmitterManager.subscribe("announcement", wxOpenId, SSE_TIMEOUT);

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100);

                // 构建初始状态数据
                Map<String, Object> statusData = new HashMap<>();
                statusData.put("latestId", announcementCacheUtil.getLatestId());
                statusData.put("totalCount", announcementCacheUtil.getTotalCount());
                // ⭐ 修改：使用 AnnouncementCacheUtil 判断系统是否可用
                statusData.put("operational", StringUtils.hasText(announcementCacheUtil.getActiveSourceOpenId()));
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
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("scheduleConnections", sseEmitterManager.getConnectionCount("schedule"));
        status.put("announcementConnections", sseEmitterManager.getConnectionCount("announcement"));
        status.put("calendarConnections", sseEmitterManager.getConnectionCount("calendar"));
        status.put("totalConnections", sseEmitterManager.getTotalConnectionCount());
        return status;
    }
}