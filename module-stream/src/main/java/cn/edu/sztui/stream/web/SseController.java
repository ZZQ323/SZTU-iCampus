package cn.edu.sztui.stream.web;

import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.result.Result;
import cn.edu.sztui.stream.infrastructure.util.sse.SseEmitterManager;
import cn.edu.sztui.stream.infrastructure.util.sse.dto.SseMessage;
import cn.edu.sztui.stream.infrastructure.util.stream.StreamKeys;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

/**
 * SSE 连接控制器
 * 
 * 提供 Server-Sent Events 实时推送接口
 * 
 * 文件位置：module-stream/src/main/java/cn/edu/sztui/stream/web/SseController.java
 */
@Slf4j
@RestController
@RequestMapping("/sse")
@Tag(name = "SSE推送接口", description = "Server-Sent Events 实时推送")
public class SseController {

    @Resource
    private SseEmitterManager sseEmitterManager;

    /** 默认超时时间: 30分钟 */
    private static final long DEFAULT_TIMEOUT = 30 * 60 * 1000L;

    // ==================== 订阅接口 ====================

    /**
     * 订阅课表更新
     */
    @GetMapping(value = "/v1/schedule", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅课表更新", description = "建立 SSE 连接，接收课表实时更新")
    public SseEmitter subscribeSchedule(
            @Parameter(description = "超时时间(毫秒)，默认30分钟")
            @RequestParam(required = false) Long timeout) {
        
        String wxOpenId = UserContext.getContext().getOpenId();
        long actualTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        
        log.info("用户 {} 订阅课表更新", wxOpenId);
        
        SseEmitter emitter = sseEmitterManager.subscribe("schedule", wxOpenId, actualTimeout);
        
        // 发送连接成功消息
        sendConnectedMessage(emitter, "schedule");
        
        return emitter;
    }

    /**
     * 订阅公告更新
     */
    @GetMapping(value = "/v1/announcement", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅公告更新", description = "建立 SSE 连接，接收公告实时更新")
    public SseEmitter subscribeAnnouncement(
            @Parameter(description = "超时时间(毫秒)，默认30分钟")
            @RequestParam(required = false) Long timeout) {
        
        String wxOpenId = UserContext.getContext().getOpenId();
        long actualTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        
        log.info("用户 {} 订阅公告更新", wxOpenId);
        
        SseEmitter emitter = sseEmitterManager.subscribe("announcement", wxOpenId, actualTimeout);
        
        // 发送连接成功消息
        sendConnectedMessage(emitter, "announcement");
        
        return emitter;
    }

    /**
     * 订阅日历/活动更新
     */
    @GetMapping(value = "/v1/calendar", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅日历更新", description = "建立 SSE 连接，接收日历/活动实时更新")
    public SseEmitter subscribeCalendar(
            @Parameter(description = "超时时间(毫秒)，默认30分钟")
            @RequestParam(required = false) Long timeout) {
        
        String wxOpenId = UserContext.getContext().getOpenId();
        long actualTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        
        log.info("用户 {} 订阅日历更新", wxOpenId);
        
        SseEmitter emitter = sseEmitterManager.subscribe("calendar", wxOpenId, actualTimeout);
        
        // 发送连接成功消息
        sendConnectedMessage(emitter, "calendar");
        
        return emitter;
    }

    /**
     * 统一订阅接口（订阅指定 topic）
     */
    @GetMapping(value = "/v1/subscribe/{topic}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅指定主题", description = "建立 SSE 连接，订阅指定主题的实时更新")
    public SseEmitter subscribe(
            @Parameter(description = "主题: schedule/announcement/calendar", required = true)
            @PathVariable String topic,
            @Parameter(description = "超时时间(毫秒)，默认30分钟")
            @RequestParam(required = false) Long timeout) {
        
        String wxOpenId = UserContext.getContext().getOpenId();
        long actualTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        
        log.info("用户 {} 订阅主题: {}", wxOpenId, topic);
        
        SseEmitter emitter = sseEmitterManager.subscribe(topic, wxOpenId, actualTimeout);
        
        // 发送连接成功消息
        sendConnectedMessage(emitter, topic);
        
        return emitter;
    }

    // ==================== 取消订阅 ====================

    /**
     * 取消订阅
     */
    @PostMapping("/v1/unsubscribe/{topic}")
    @Operation(summary = "取消订阅", description = "关闭指定主题的 SSE 连接")
    public Result unsubscribe(
            @Parameter(description = "主题: schedule/announcement/calendar", required = true)
            @PathVariable String topic) {
        
        String wxOpenId = UserContext.getContext().getOpenId();
        
        log.info("用户 {} 取消订阅主题: {}", wxOpenId, topic);
        
        sseEmitterManager.unsubscribe(topic, wxOpenId);
        
        return Result.ok();
    }

    // ==================== 状态查询 ====================

    /**
     * 获取连接状态
     */
    @GetMapping("/v1/status")
    @Operation(summary = "获取连接状态", description = "获取当前用户的 SSE 连接状态")
    public Result getStatus() {
        String wxOpenId = UserContext.getContext().getOpenId();
        
        Map<String, Object> status = new HashMap<>();
        status.put("schedule", sseEmitterManager.isSubscribed("schedule", wxOpenId));
        status.put("announcement", sseEmitterManager.isSubscribed("announcement", wxOpenId));
        status.put("calendar", sseEmitterManager.isSubscribed("calendar", wxOpenId));
        
        return Result.ok(status);
    }

    /**
     * 获取系统连接统计
     */
    @GetMapping("/v1/stats")
    @Operation(summary = "获取系统连接统计", description = "获取所有 SSE 连接的统计信息")
    public Result getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("schedule", sseEmitterManager.getConnectionCount("schedule"));
        stats.put("announcement", sseEmitterManager.getConnectionCount("announcement"));
        stats.put("calendar", sseEmitterManager.getConnectionCount("calendar"));
        stats.put("total", sseEmitterManager.getTotalConnectionCount());
        
        return Result.ok(stats);
    }

    // ==================== 私有方法 ====================

    /**
     * 发送连接成功消息
     */
    private void sendConnectedMessage(SseEmitter emitter, String topic) {
        try {
            SseMessage<Map<String, Object>> message = SseMessage.data(
                    StreamKeys.TYPE_CONNECTED,
                    Map.of(
                            "topic", topic,
                            "message", "连接成功"
                    )
            );
            
            emitter.send(SseEmitter.event()
                    .name(StreamKeys.TYPE_CONNECTED)
                    .data(message));
                    
        } catch (Exception e) {
            log.warn("发送连接成功消息失败: {}", e.getMessage());
        }
    }
}
