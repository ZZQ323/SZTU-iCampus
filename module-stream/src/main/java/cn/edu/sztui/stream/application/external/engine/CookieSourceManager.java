package cn.edu.sztui.stream.application.external.engine;

import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.common.util.smarthttp.SmartCookieConverter;
import cn.edu.sztui.common.util.smarthttp.dto.SmartCookie;
import cn.edu.sztui.common.util.smarthttp.service.SmartHttpClient;
import cn.edu.sztui.common.util.smarthttp.service.SmartSession;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
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

    public String getAvailableUserId() {
        String active = infoCacheUtil.getActiveSourceUserId();
        if (StringUtils.hasText(active) && isValid(active)) {
            return active;
        }

        String found = findValidFromOnlineUsers();
        if (found != null) {
            infoCacheUtil.setActiveSourceUserId(found);
            log.info("切换到新 Cookie 来源: {}", found);
            return found;
        }

        infoCacheUtil.clearActiveSource();
        return null;
    }

    public void markInvalidAndSwitch(String invalidUserId) {
        infoCacheUtil.clearActiveSource();
        String newSource = findValidFromOnlineUsers();
        if (newSource != null && !newSource.equals(invalidUserId)) {
            infoCacheUtil.setActiveSourceUserId(newSource);
            log.info("Cookie 来源切换: {} → {}", invalidUserId, newSource);
        }
    }

    public boolean hasAvailableCookie() {
        return getAvailableUserId() != null;
    }

    private boolean isValid(String userId) {
        if (!StringUtils.hasText(userId)) return false;
        if (!authSessionCacheUtil.hasSession(userId)) return false;
        return authSessionCacheUtil.isSchoolLoggedIn(userId);
    }

    private String findValidFromOnlineUsers() {
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