package cn.edu.sztui.base.application.listener;

import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.common.util.auth.CookieAccessEvent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 监听 CookieAccessEvent，异步将用户 cookies 刷新到 Redis
 * <p>
 * 效果：只要用户在使用小程序（发任意认证请求），Redis 中就有他的 cookie 供爬虫引擎使用。
 * 使用 5 分钟节流避免频繁写入。
 */
@Slf4j
@Component
public class CookieAccessEventListener {

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    @Async
    @EventListener
    public void onCookieAccess(CookieAccessEvent event) {
        try {
            authSessionCacheUtil.refreshIfNeeded(event.getUserId(), event.getCookiesJson());
        } catch (Exception e) {
            log.warn("刷新 Cookie 池失败: userId={}, error={}", event.getUserId(), e.getMessage());
        }
    }
}
