package cn.edu.sztui.common.util.auth;

import cn.edu.sztui.common.util.bean.TokenMessage;
import cn.edu.sztui.common.util.jwt.JwtConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
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

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    @Autowired
    private JwtConfig jwtConfig;
    @Autowired
    private ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 公开接口，无需登录
     */
    private static final List<String> PUBLIC_PATHS = Arrays.asList(
            "/wx-auth/v1/get-token",
            "/wx-auth/v1/refresh-token",
            "/notice/list",
            "/calendar/**"
    );

    /**
     * 可选认证接口（有 token 就解析，没有也放行）
     */
    private static final List<String> OPTIONAL_AUTH_PATHS = Arrays.asList(
            // 如果有些接口希望匿名也能访问，加在这里
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String path = request.getServletPath();

            // 1. 公开接口直接放行
            if (isPublicPath(path)) {
                chain.doFilter(request, response);
                return;
            }

            // 2. 获取 Token
            String token = getTokenFromRequest(request);

            // 3. 判断是否是可选认证接口
            boolean isOptionalAuth = isOptionalAuthPath(path);

            // 4. 无 token 的处理
            if (!StringUtils.hasText(token)) {
                if (isOptionalAuth) {
                    // 可选认证：无 token 也放行
                    chain.doFilter(request, response);
                } else {
                    // 必须认证：返回 401
                    writeErrorResponse(response, HttpStatus.UNAUTHORIZED, "请先登录");
                }
                return;
            }

            // 5. 验证 Token
            JwtConfig.TokenValidationResult result = jwtConfig.validateToken(token);

            if (!result.isValid()) {
                if (result.isExpired()) {
                    writeErrorResponse(response, HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
                } else {
                    writeErrorResponse(response, HttpStatus.FORBIDDEN, result.getMessage());
                }
                return;
            }

            // 6. 构建用户上下文
            Claims claims = result.getClaims();
            TokenMessage context = new TokenMessage();
            context.setOpenId((String) claims.get("openid"));
            context.setUnionId((String) claims.get("unionid"));
            context.setSessionKey((String) claims.get("sessionkey"));

            // 7. 存入 ThreadLocal
            UserContext.setContext(context);

            // 8. 继续执行
            chain.doFilter(request, response);
        } finally {
            // 9. 清理 ThreadLocal
            UserContext.clear();
        }
    }

    private String getTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader(jwtConfig.getHeader());
        // 处理 "Bearer xxx" 格式
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        // 也支持直接传 token
        if (StringUtils.hasText(bearerToken)) {
            return bearerToken;
        }
        // 兼容从参数获取
        return request.getParameter("token");
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private boolean isOptionalAuthPath(String path) {
        return OPTIONAL_AUTH_PATHS.stream()
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