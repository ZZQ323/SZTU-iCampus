package cn.edu.sztui.stream.infrastructure.websocket.config;

import cn.edu.sztui.stream.infrastructure.websocket.handler.UnifiedWsHandler;
import cn.edu.sztui.stream.infrastructure.websocket.interceptor.WsAuthInterceptor;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置
 * <p>
 * 注册唯一的 WebSocket 端点 /ws，通过 query param 区分 topic 和认证：
 * <pre>
 * ws://host:port/ws?token=xxx&topics=announcement,schedule
 * </pre>
 * <p>
 * 文件位置：module-stream/.../infrastructure/websocket/config/WebSocketConfig.java
 */
@Slf4j
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private UnifiedWsHandler unifiedWsHandler;

    @Resource
    private WsAuthInterceptor wsAuthInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(unifiedWsHandler, "/ws")
                .addInterceptors(wsAuthInterceptor)
                .setAllowedOrigins("*");  // 小程序端无 Origin，需要放开

        log.info("WebSocket 端点注册完成: /ws");
    }
}