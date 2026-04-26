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
     * <p>
     * **硬规则**（2026-04-25 重写）：能被借去爬的 cookies，**必须同时满足**：
     *   1. WS 在线（{@code wsSessionRegistry.isOnline}）
     *   2. schoolLoggedIn = true
     *   3. Redis 有 cookies
     * 三者缺一不可。否则用户登出 / 关掉小程序后，cookies 还会被后端默默借去爬学校，
     * 学校会议这条 IP 一直在动 → 风险敞口（封号、cookie 寿命被人为延长）。
     * <p>
     * 旧版有第三层"兜底"逻辑（findValidFromAllSessions），无视在线状态找
     * Redis 里任意 schoolLoggedIn=true 的用户用 —— 那是逆天设计，已删除。
     * 找不到合适用户就返回 null，调用方（scheduler）整轮跳过即可。
     */
    public String getAvailableUserId() {
        // 1. 当前活跃用户如果在线且有效，直接复用（快速路径）
        String active = infoCacheUtil.getActiveSourceUserId();
        if (StringUtils.hasText(active) && wsSessionRegistry.isOnline(active) && isValid(active)) {
            return active;
        }

        // 2. 从 WS 在线用户中找一个 valid 的
        for (String onlineUser : wsSessionRegistry.getOnlineUserIds()) {
            if (isValid(onlineUser)) {
                infoCacheUtil.setActiveSourceUserId(onlineUser);
                log.info("切换到在线用户 Cookie: {}", onlineUser);
                return onlineUser;
            }
        }

        // 没有任何在线 + valid 的用户 → 不爬。**绝不能借离线用户的 cookies**。
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

    /**
     * 检验 userId 是否能被借去爬：必须有 session 数据（cookies 还在）+ schoolLoggedIn=true。
     * 在线状态由调用方在调用此方法之前的位置自行检查。
     */
    private boolean isValid(String userId) {
        if (!StringUtils.hasText(userId)) return false;
        if (!authSessionCacheUtil.hasSession(userId)) return false;
        ProxySession s = authSessionCacheUtil.getSession(userId);
        if (s == null || !StringUtils.hasText(s.getCookiesJson())) return false;
        return authSessionCacheUtil.isSchoolLoggedIn(userId);
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