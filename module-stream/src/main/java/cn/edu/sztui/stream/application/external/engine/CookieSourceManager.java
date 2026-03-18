package cn.edu.sztui.stream.application.external.engine;

import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.common.util.smarthttp.SmartCookieConverter;
import cn.edu.sztui.common.util.smarthttp.dto.SmartCookie;
import cn.edu.sztui.common.util.smarthttp.service.SmartHttpClient;
import cn.edu.sztui.common.util.smarthttp.service.SmartSession;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
        String openId = getAvailableOpenId();
        if (openId == null) {
            throw new NoCookieAvailableException("无可用的 Cookie 来源");
        }

        ProxySession proxy = authSessionCacheUtil.getSession(openId);
        if (proxy == null || !StringUtils.hasText(proxy.getCookiesJson())) {
            markInvalidAndSwitch(openId);
            throw new NoCookieAvailableException("Cookie 来源失效: " + openId);
        }

        // ★ 关键：从 cookiesJson 反序列化为 SmartCookie 列表，再创建 SmartSession
        List<SmartCookie> cookies = SmartCookieConverter.jsonToSmartCookies(proxy.getCookiesJson());
        return smartHttpClient.newSession(cookies);
    }

    public String getAvailableOpenId() {
        String active = infoCacheUtil.getActiveSourceOpenId();
        if (StringUtils.hasText(active) && isValid(active)) {
            return active;
        }

        String found = findValidFromOnlineUsers();
        if (found != null) {
            infoCacheUtil.setActiveSourceOpenId(found);
            log.info("切换到新 Cookie 来源: {}", found);
            return found;
        }

        infoCacheUtil.clearActiveSource();
        return null;
    }

    public void markInvalidAndSwitch(String invalidOpenId) {
        infoCacheUtil.clearActiveSource();
        String newSource = findValidFromOnlineUsers();
        if (newSource != null && !newSource.equals(invalidOpenId)) {
            infoCacheUtil.setActiveSourceOpenId(newSource);
            log.info("Cookie 来源切换: {} → {}", invalidOpenId, newSource);
        }
    }

    public boolean hasAvailableCookie() {
        return getAvailableOpenId() != null;
    }

    private boolean isValid(String openId) {
        if (!StringUtils.hasText(openId)) return false;
        if (!authSessionCacheUtil.hasSession(openId)) return false;
        return authSessionCacheUtil.isSchoolLoggedIn(openId);
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

    public static class NoCookieAvailableException extends RuntimeException {
        public NoCookieAvailableException(String message) {
            super(message);
        }
    }
}