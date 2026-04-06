package cn.edu.sztui.stream.infrastructure.websocket.interceptor;

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
 * 握手 URL：ws://host:port/ws?openId=xxx&topics=announcement,schedule
 * <p>
 * 从 query param 读取 openId（前端登录后已获取）。
 * 不再使用 JWT 验证。
 */
@Slf4j
@Component
public class WsAuthInterceptor implements HandshakeInterceptor {

    public static final String ATTR_OPEN_ID = "ws.openId";
    public static final String ATTR_TOPICS = "ws.topics";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            log.warn("WS 握手拒绝: 非 Servlet 请求");
            return false;
        }

        // 从 query param 读取 openId
        String openId = servletRequest.getServletRequest().getParameter("openId");
        if (!StringUtils.hasText(openId)) {
            log.warn("WS 握手拒绝: 缺少 openId 参数");
            return false;
        }

        // 提取 topics
        String topics = servletRequest.getServletRequest().getParameter("topics");
        if (!StringUtils.hasText(topics)) {
            topics = "announcement";
        }

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
