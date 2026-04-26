package cn.edu.sztui.stream.application.external.listener;

import cn.edu.sztui.base.domain.event.UserLogoutEvent;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import cn.edu.sztui.stream.infrastructure.websocket.registry.WsSessionRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 用户登出事件监听器。
 * <p>
 * 收到 {@link UserLogoutEvent} 后做两件事：
 * <ol>
 *   <li>主动踢断该 user 的 WS 连接 —— 防止 in-flight 爬虫推过来的 COOKIE_UPDATE
 *       落进已登出但 WS 还连着的前端，把本地 cookies 复活</li>
 *   <li>如果 active source userId 是这个人，立刻清掉 —— 让 CookieSourceManager 下次
 *       getAvailableUserId 必须重选</li>
 * </ol>
 * <p>
 * 同步执行（非 @Async），保证 logout API 返回前 WS 已断、active source 已清，前端
 * 看到 logout 成功的瞬间状态就是干净的。
 */
@Slf4j
@Component
public class UserLogoutEventListener {

    @Resource
    private WsSessionRegistry wsSessionRegistry;

    @Resource
    private InfoCacheUtil infoCacheUtil;

    @EventListener
    public void onUserLogout(UserLogoutEvent event) {
        String userId = event.getUserId();
        if (!StringUtils.hasText(userId)) return;

        log.info("收到用户登出事件: userId={}, 踢 WS + 清 active source", userId);

        // 1. 踢 WS（前端会感知断开）
        wsSessionRegistry.kickUser(userId);

        // 2. 清 active source（如果当前活跃的就是这个人）
        String active = infoCacheUtil.getActiveSourceUserId();
        if (userId.equals(active)) {
            infoCacheUtil.clearActiveSource();
            log.info("active source 已清: 原值={}", active);
        }
    }
}
