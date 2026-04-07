package cn.edu.sztui.common.util.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Cookie 响应头过滤器
 * <p>
 * 类比 CookieAuthFilter（请求侧），这是响应侧过滤器。
 * <p>
 * 职责：
 * 1. 为所有响应添加 Access-Control-Expose-Headers: X-Set-Cookies，
 *    使小程序前端能读取 X-Set-Cookies 响应头。
 * 2. 处理 CORS preflight (OPTIONS) 请求，避免被 CookieAuthFilter 拦截返回 401。
 * <p>
 * 任何 Controller 只需 response.setHeader("X-Set-Cookies", json) 即可，
 * 前端自动能读取 —— 这就是统一的 cookie 返回接口。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CookieResponseFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // 所有响应都暴露 X-Set-Cookies header，使前端能读取
        response.setHeader("Access-Control-Expose-Headers", "X-Set-Cookies");

        // CORS preflight：OPTIONS 请求没有自定义 header，不能让 CookieAuthFilter 拦截
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setHeader("Access-Control-Allow-Origin", "*");
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "X-School-Cookies, X-User-Id, Content-Type");
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        chain.doFilter(request, response);
    }
}
