package cn.edu.sztui.common.util.auth;

import cn.edu.sztui.common.util.bean.TokenMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cookie 认证过滤器（替代 JwtAuthFilter）
 * <p>
 * 从请求 header 读取 X-Open-Id 和 X-School-Cookies，
 * 构建 UserContext 供下游使用。
 * <p>
 * 不做 JWT 签名验证、不做过期判断。
 * Cookie 有效性由学校返回的页面内容判断（在业务层处理）。
 */
@Component
public class CookieAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_OPEN_ID = "X-Open-Id";
    public static final String HEADER_COOKIES = "X-School-Cookies";

    @Autowired
    private ObjectMapper objectMapper;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 公开接口，无需认证
     */
    private static final List<String> PUBLIC_PATHS = Arrays.asList(
            "/auth/v1/session/init",
            "/auth/v1/login",
            "/auth/v1/request/sms",
            "/notice/list",
            "/calendar/**"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String path = request.getServletPath();

            // 公开接口直接放行
            if (isPublicPath(path)) {
                chain.doFilter(request, response);
                return;
            }

            // 读取 openId
            String openId = request.getHeader(HEADER_OPEN_ID);
            if (!StringUtils.hasText(openId)) {
                // 也支持从参数获取（WebSocket 握手兼容）
                openId = request.getParameter("openId");
            }

            if (!StringUtils.hasText(openId)) {
                writeErrorResponse(response, HttpStatus.UNAUTHORIZED, "请先登录");
                return;
            }

            // 构建用户上下文
            TokenMessage context = new TokenMessage();
            context.setOpenId(openId);

            // cookies 是可选的（有些接口只需要身份标识，不需要代理）
            String cookiesJson = request.getHeader(HEADER_COOKIES);
            if (StringUtils.hasText(cookiesJson)) {
                context.setSchoolCookiesJson(cookiesJson);
            }

            UserContext.setContext(context);
            chain.doFilter(request, response);

        } finally {
            UserContext.clear();
        }
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private void writeErrorResponse(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> result = new HashMap<>();
        result.put("code", status.value());
        result.put("message", message);
        result.put("data", null);

        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
