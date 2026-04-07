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
 * Cookie 认证过滤器
 * <p>
 * 所有请求都尝试从 header 读取 X-School-Cookies 和 X-User-Id，
 * 构建 UserContext 供下游使用。
 * <p>
 * 公开接口：有 cookie 就读，没有不拒绝。
 * 非公开接口：必须有 cookie，否则 401。
 * <p>
 * Cookie 有效性由学校返回的页面内容判断（在业务层处理）。
 */
@Component
public class CookieAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_COOKIES = "X-School-Cookies";

    @Autowired
    private ObjectMapper objectMapper;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 公开接口，cookie 可选
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
            String cookiesJson = request.getHeader(HEADER_COOKIES);

            // 所有路径：有 cookie 就读取并设置 UserContext
            if (StringUtils.hasText(cookiesJson)) {
                TokenMessage context = new TokenMessage();
                context.setSchoolCookiesJson(cookiesJson);

                String userId = request.getHeader(HEADER_USER_ID);
                if (!StringUtils.hasText(userId)) {
                    userId = request.getParameter("userId");
                }
                if (StringUtils.hasText(userId)) {
                    context.setUserId(userId);
                }

                UserContext.setContext(context);
            }

            // 非公开路径：必须有 cookies
            if (!isPublicPath(path) && !StringUtils.hasText(cookiesJson)) {
                writeErrorResponse(response, HttpStatus.UNAUTHORIZED, "请先登录");
                return;
            }

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
