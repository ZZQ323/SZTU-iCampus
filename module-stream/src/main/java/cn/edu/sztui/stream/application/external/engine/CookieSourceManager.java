package cn.edu.sztui.stream.application.external.engine;

import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.common.util.smarthttp.SmartCookieConverter;
import cn.edu.sztui.common.util.smarthttp.dto.SmartCookie;
import cn.edu.sztui.common.util.smarthttp.service.SmartHttpClient;
import cn.edu.sztui.common.util.smarthttp.service.SmartSession;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import cn.edu.sztui.stream.infrastructure.websocket.registry.WsSessionRegistry;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cookie 来源管理器
 * <p>
 * 从在线用户中获取可用于爬取的 Cookie。
 * 仅供 requiresAuth=true 的源使用。
 */
@Slf4j
@Component
public class CookieSourceManager {

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    @Resource
    private SmartHttpClient smartHttpClient;

    @Resource
    private InfoCacheUtil infoCacheUtil;

    @Resource
    private WsSessionRegistry wsSessionRegistry;

    /**
     * 获取一个可用的 SmartSession（带学校 Cookie）
     */
    public SmartSession getAvailableSession() {
        return getAvailableSessionWithUser().getSession();
    }

    /**
     * 获取可用的 SmartSession 及其所属 userId（供 CrawlEngine 检测 cookie 变化用）
     */
    public CookieSessionPair getAvailableSessionWithUser() {
        String userId = getAvailableUserId();
        if (userId == null) {
            throw new NoCookieAvailableException("无可用的 Cookie 来源");
        }

        ProxySession proxy = authSessionCacheUtil.getSession(userId);
        if (proxy == null || !StringUtils.hasText(proxy.getCookiesJson())) {
            markInvalidAndSwitch(userId);
            throw new NoCookieAvailableException("Cookie 来源失效: " + userId);
        }

        // 从 cookiesJson 反序列化为 SmartCookie 列表，再创建 SmartSession
        List<SmartCookie> cookies = SmartCookieConverter.jsonToSmartCookies(proxy.getCookiesJson());
        // 保存原始 cookies 快照，用于爬取后比对变化
        List<SmartCookie> originalSnapshot = new ArrayList<>(cookies);
        SmartSession session = smartHttpClient.newSession(cookies);
        return new CookieSessionPair(userId, session, originalSnapshot);
    }

    /**
     * 获取可用的 Cookie 来源用户。
     * 优先级：当前活跃用户（在线+有效）→ 在线用户 → Redis 所有 session
     */
    public String getAvailableUserId() {
        // 1. 当前活跃用户如果在线且有效，直接复用
        String active = infoCacheUtil.getActiveSourceUserId();
        if (StringUtils.hasText(active) && wsSessionRegistry.isOnline(active) && isValid(active)) {
            return active;
        }

        // 2. 优先从 WS 在线用户中找
        for (String onlineUser : wsSessionRegistry.getOnlineUserIds()) {
            if (isValid(onlineUser)) {
                infoCacheUtil.setActiveSourceUserId(onlineUser);
                log.info("切换到在线用户 Cookie: {}", onlineUser);
                return onlineUser;
            }
        }

        // 3. 退而求其次：Redis 中所有 session（可能已离线但 cookie 还没过期）
        String found = findValidFromAllSessions();
        if (found != null) {
            infoCacheUtil.setActiveSourceUserId(found);
            log.info("切换到离线用户 Cookie（无在线用户可用）: {}", found);
            return found;
        }

        infoCacheUtil.clearActiveSource();
        return null;
    }

    public void markInvalidAndSwitch(String invalidUserId) {
        log.warn("Cookie 来源失效: {}", invalidUserId);
        infoCacheUtil.clearActiveSource();
        // 尝试切换到其他可用用户
        String newSource = getAvailableUserId();
        if (newSource != null && !newSource.equals(invalidUserId)) {
            log.info("Cookie 来源切换: {} → {}", invalidUserId, newSource);
        }
    }

    public boolean hasAvailableCookie() {
        return getAvailableUserId() != null;
    }

    /**
     * 返回当前在线且 Redis session 里有 jwxt 子域 cookies 的用户列表。
     * <p>
     * 用于教务内网（acdm-*）轮询：只能用"调用过 /acdm/v1/init 的用户"的 cookies，
     * 不能复用公文通的通用 cookie 池逻辑。一个 userId 没出现在这里 = 没 init 过或 session 已被清。
     */
    public List<String> getOnlineUsersWithAcdmCookies() {
        List<String> result = new ArrayList<>();
        for (String userId : wsSessionRegistry.getOnlineUserIds()) {
            if (!isValid(userId)) continue;
            ProxySession proxy = authSessionCacheUtil.getSession(userId);
            if (proxy == null || !StringUtils.hasText(proxy.getCookiesJson())) continue;
            List<SmartCookie> cookies = SmartCookieConverter.jsonToSmartCookies(proxy.getCookiesJson());
            if (hasAcdmCookie(cookies)) {
                result.add(userId);
            }
        }
        return result;
    }

    /**
     * 判断 cookie 列表里是否有 jwxt 子域的 cookie（即该用户跑过教务 init）。
     */
    public static boolean hasAcdmCookie(List<SmartCookie> cookies) {
        if (cookies == null) return false;
        for (SmartCookie c : cookies) {
            String d = c.getDomain();
            if (d != null && d.toLowerCase().contains("jwxt-sztu-edu-cn")) {
                return true;
            }
        }
        return false;
    }

    private boolean isValid(String userId) {
        if (!StringUtils.hasText(userId)) return false;
        if (!authSessionCacheUtil.hasSession(userId)) return false;
        return authSessionCacheUtil.isSchoolLoggedIn(userId);
    }

    /**
     * 从 Redis 所有 ProxySession 中找一个有效的（不限是否在线）
     */
    private String findValidFromAllSessions() {
        Map<String, ProxySession> allSessions = authSessionCacheUtil.getAllSessions();
        if (allSessions == null || allSessions.isEmpty()) return null;

        for (Map.Entry<String, ProxySession> entry : allSessions.entrySet()) {
            if (entry.getValue().isSchoolLoggedIn() && isValid(entry.getKey())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 携带 userId 和原始 cookies 快照的 session 包装
     */
    @Getter
    @AllArgsConstructor
    public static class CookieSessionPair {
        private final String userId;
        private final SmartSession session;
        private final List<SmartCookie> originalCookies;
    }

    public static class NoCookieAvailableException extends RuntimeException {
        public NoCookieAvailableException(String message) {
            super(message);
        }
    }
}