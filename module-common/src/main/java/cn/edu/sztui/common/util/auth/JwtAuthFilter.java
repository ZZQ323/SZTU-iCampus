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
            "/notice/list",      // 公告列表
            "/calendar/**"      // 活动日历
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException, IOException {
        try {
            String path = request.getServletPath();
            // 公开接口直接放行
            if (isPublicPath(path)) {
                chain.doFilter(request, response);
                return;
            }
            // 获取 Token
            String token = getTokenFromRequest(request);
            if (!StringUtils.hasText(token)) {
                // 无 token，但也允许访问（匿名用户）
                // 如果某些接口必须登录，可以在 Controller 层判断
                chain.doFilter(request, response);
                return;
            }
            // 验证 Token
            JwtConfig.TokenValidationResult result = jwtConfig.validateToken(token);

            if (!result.isValid()) {
                if (result.isExpired()) {
                    writeErrorResponse(response, HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
                } else {
                    writeErrorResponse(response, HttpStatus.FORBIDDEN, result.getMessage());
                }
                return;
            }
            // 构建用户上下文
            Claims claims = result.getClaims();
            TokenMessage context = new TokenMessage();
            context.setUnionId(claims.getSubject());
            context.setOpenId((String) claims.get("openid"));           // 添加
            context.setUnionId((String) claims.get("unionid"));           // 添加
            context.setSessionKey((String) claims.get("sessionkey"));   // 添加
            // 5. 存入 ThreadLocal
            UserContext.setContext(context);
            // 6. 继续执行
            chain.doFilter(request, response);
        } finally {
            // 7. 清理 ThreadLocal
            UserContext.clear();
        }
    }

    /**
     * 从请求中获取 Token
     */
    private String getTokenFromRequest(HttpServletRequest request) {
        // 优先从 Header 获取
        String token = request.getHeader(jwtConfig.getHeader());
        // 也支持从请求参数获取（兼容某些场景）
        if (!StringUtils.hasText(token))
            token = request.getParameter("token");
        return token;
    }

    /**
     * 判断是否为公开接口
     */
    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    /**
     * 写入错误响应
     */
    private void writeErrorResponse(HttpServletResponse response,
                                    HttpStatus status,
                                    String message) throws IOException {
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