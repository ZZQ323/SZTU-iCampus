package cn.edu.sztui.stream.infrastructure.websocket.interceptor;

import cn.edu.sztui.common.util.jwt.JwtConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 握手鉴权拦截器
 * <p>
 * 握手 URL：ws://host:port/ws?token=xxx&topics=announcement,schedule
 * <p>
 * 使用 JwtConfig.validateToken() 做严格验证：
 * - valid → 握手通过，存 openId 到 attributes
 * - expired → 握手拒绝（前端应先 refresh-token 再连 WS）
 * - invalid → 握手拒绝
 * <p>
 * 文件位置：module-stream/.../infrastructure/websocket/interceptor/WsAuthInterceptor.java
 */
@Slf4j
@Component
public class WsAuthInterceptor implements HandshakeInterceptor {

    public static final String ATTR_OPEN_ID = "ws.openId";
    public static final String ATTR_TOPICS = "ws.topics";

    @Resource
    private JwtConfig jwtConfig;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            log.warn("WS 握手拒绝: 非 Servlet 请求");
            return false;
        }

        // 1. 提取 token
        String token = servletRequest.getServletRequest().getParameter("token");
        if (!StringUtils.hasText(token)) {
            log.warn("WS 握手拒绝: 缺少 token 参数");
            return false;
        }

        // 2. 严格验证 token（不允许过期 token 建立连接）
        JwtConfig.TokenValidationResult result = jwtConfig.validateToken(token);

        if (!result.isValid()) {
            if (result.isExpired()) {
                log.warn("WS 握手拒绝: token 已过期，请先刷新 token");
            } else {
                log.warn("WS 握手拒绝: {}", result.getMessage());
            }
            return false;
        }

        // 3. 提取 openId
        String openId = result.getClaims().getSubject();
        if (!StringUtils.hasText(openId)) {
            log.warn("WS 握手拒绝: token 中无 openId");
            return false;
        }

        // 4. 提取 topics
        String topics = servletRequest.getServletRequest().getParameter("topics");
        if (!StringUtils.hasText(topics)) {
            topics = "announcement";
        }

        // 5. 存入 attributes
        attributes.put(ATTR_OPEN_ID, openId);
        attributes.put(ATTR_TOPICS, topics);

        log.info("WS 握手通过: openId={}, topics={}", openId, topics);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}