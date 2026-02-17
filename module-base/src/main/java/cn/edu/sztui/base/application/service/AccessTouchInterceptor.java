package cn.edu.sztui.base.application.service;

import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.bean.TokenMessage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 访问活跃度拦截器 —— 注册在 base 服务的 WebMvcConfigurer 中
 * <p>
 * 职责：每次有效请求（UserContext 不为空）时，更新 Redis 中的 lastAccessTime。
 * <p>
 * 为什么不在 Filter 中做？
 * 因为 JwtAuthFilter 在 common 包，不能依赖 base 的 AuthSessionCacheUtil / TokenRefreshService。
 * 所以用 Spring MVC 的 HandlerInterceptor（在 base 服务注册），在 Filter 之后执行。
 */
@Component
public class AccessTouchInterceptor implements HandlerInterceptor
{

    @Autowired
    private TokenRefreshService tokenRefreshService;

    @Override
    public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler)
    {
        TokenMessage context = UserContext.getContext();
        // 异步优化：如果并发量大，可以考虑用异步方式更新
        if (context != null && context.getOpenId() != null) tokenRefreshService.touch(context.getOpenId());
        return true;  // 始终放行，不阻断请求
    }
}
