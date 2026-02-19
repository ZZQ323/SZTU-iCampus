package cn.edu.sztui.base.web;

import cn.edu.sztui.base.application.dto.query.CrouseTableQuery;
import cn.edu.sztui.base.application.service.AcademicService;
import cn.edu.sztui.base.application.vo.CourseTableVO;
import cn.edu.sztui.base.infrastructure.sse.SseEmitterManager;
import cn.edu.sztui.base.infrastructure.sse.dto.SseMessage;
import cn.edu.sztui.base.infrastructure.stream.StreamKeys;
import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.bean.TokenMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import java.util.concurrent.CompletableFuture;

/**
 * SSE 订阅控制器
 * 
 * 提供 Server-Sent Events 流式推送接口
 */
@Slf4j
@RestController
@RequestMapping("/stream")
public class SseController {
    
    @Resource
    private SseEmitterManager sseEmitterManager;
    
    @Resource
    private AcademicService academicService;
    
    /** SSE 连接超时时间: 30分钟 */
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;
    
    /**
     * 订阅课表更新流
     * 
     * 连接建立后会立即推送当前课表数据，之后会在数据更新时推送增量
     * 
     * @return SSE Emitter
     */
    @GetMapping(value = "/schedule", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeSchedule() {
        TokenMessage token = UserContext.getContext();
        String wxOpenId = token.getOpenId();
        
        log.info("用户 {} 订阅课表流", wxOpenId);
        
        // 注册 SSE 连接
        SseEmitter emitter = sseEmitterManager.subscribe("schedule", wxOpenId, SSE_TIMEOUT);
        
        // 异步获取当前课表并立即推送 (首次加载)
        CompletableFuture.runAsync(() -> {
            try {
                // 稍微延迟，确保 SSE 连接已建立
                Thread.sleep(100);
                
                // 获取当前课表
                CourseTableVO currentSchedule = academicService.getCrouseTable(new CrouseTableQuery());
                
                // 构建消息并推送
                SseMessage<CourseTableVO> message = SseMessage.data(
                        StreamKeys.TYPE_SCHEDULE_DATA, 
                        currentSchedule
                );
                
                sseEmitterManager.sendToUser("schedule", wxOpenId, message);
                log.info("已推送初始课表给用户 {}", wxOpenId);
                
            } catch (Exception e) {
                log.error("推送初始课表失败 - user: {}, error: {}", wxOpenId, e.getMessage());
                
                // 如果是认证问题，推送认证提醒
                if (e.getMessage() != null && e.getMessage().contains("登录验证失败")) {
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
        
        return sseEmitterManager.subscribe("announcement", wxOpenId, SSE_TIMEOUT);
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
     * 
     * 正常情况下客户端断开连接会自动清理，这个接口用于主动取消
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
