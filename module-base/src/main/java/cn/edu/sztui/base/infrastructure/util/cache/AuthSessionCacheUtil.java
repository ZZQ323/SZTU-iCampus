package cn.edu.sztui.base.infrastructure.util.cache;

import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.common.cache.util.CacheUtil;
import cn.edu.sztui.common.util.enums.ResultCodeEnum;
import cn.edu.sztui.common.util.enums.SysReturnCode;
import cn.edu.sztui.common.util.exception.BusinessException;
import cn.edu.sztui.common.util.smarthttp.SmartCookieConverter;
import cn.edu.sztui.common.util.smarthttp.dto.SmartCookie;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 会话缓存工具
 * <p>
 * 存储结构：
 * <ul>
 *   <li>{@code icampus:proxy-session:{userId}}  → String，value = ProxySession JSON，TTL = 3天</li>
 * </ul>
 * <p>
 * ProxySession 仅供爬虫引擎（CrawlEngine）使用，前端通过 header 直接传递 cookies。
 */
@Slf4j
@Component
public class AuthSessionCacheUtil {

    private static final String PROXY_SESSION_PREFIX = "icampus:proxy-session:";
    private static final long TTL_SECONDS = 3 * 24 * 60 * 60;

    @Resource
    private CacheUtil cacheUtil;

    // ==================== ProxySession ====================

    /**
     * 获取代理会话
     */
    public ProxySession getSession(String userId) {
        if (!StringUtils.hasText(userId)) return null;
        Object obj = cacheUtil.get(PROXY_SESSION_PREFIX + userId);
        if (obj == null) return null;
        return JSON.parseObject(obj.toString(), ProxySession.class);
    }

    /**
     * 判断代理会话是否存在
     */
    public boolean hasSession(String userId) {
        return cacheUtil.hasKey(PROXY_SESSION_PREFIX + userId);
    }

    /**
     * 删除代理会话（用于 initSession 强制重建）
     */
    public void deleteSession(String userId) {
        cacheUtil.del(PROXY_SESSION_PREFIX + userId);
        log.info("删除代理会话: userId={}", userId);
    }

    /**
     * 保存或更新 cookies
     */
    public boolean saveOrUpdateSessionCookie(String userId, List<SmartCookie> cookies) {
        if (!StringUtils.hasText(userId) || CollectionUtils.isEmpty(cookies)) {
            return false;
        }
        ProxySession session = getSession(userId);
        if (session == null) {
            session = new ProxySession();
            session.setUserId(userId);
            session.setCreateTime(System.currentTimeMillis());
            session.setUserIds(new ArrayList<>());
            session.setSchoolLoggedIn(false);
        }
        session.setCookiesJson(JSON.toJSONString(cookies));
        session.setLastUpdateTime(System.currentTimeMillis());
        saveSession(userId, session);
        log.info("保存代理会话 cookie: userId={}", userId);
        return true;
    }

    /**
     * 登录绑定（不存在则创建，一个用户一个 session）
     */
    public boolean sessionLoginBind(String userId, String studentId, List<SmartCookie> newCookies) {
        ProxySession session = getSession(userId);
        if (session == null) {
            session = new ProxySession();
            session.setUserId(userId);
            session.setCreateTime(System.currentTimeMillis());
            session.setUserIds(new ArrayList<>());
        }
        if (!session.getUserIds().contains(studentId)) {
            session.getUserIds().add(studentId);
        }
        session.setCookiesJson(JSON.toJSONString(newCookies));
        session.setLastUpdateTime(System.currentTimeMillis());
        session.setSchoolLoggedIn(true);
        saveSession(userId, session);
        log.info("用户 {} 绑定到 userId={}", studentId, userId);
        return true;
    }

    /**
     * 登出绑定（保留 Cookie，只更新状态）
     */
    public void sessionLogoutBind(String userId) {
        ProxySession session = getSession(userId);
        if (session == null) return;
        session.setLastUpdateTime(System.currentTimeMillis());
        session.setSchoolLoggedIn(false);
        saveSession(userId, session);
        log.info("userId={} 已登出学校后端", userId);
    }

    /**
     * 登出绑定（更新 Cookie 和状态）
     */
    public void sessionLogoutBind(String userId, List<SmartCookie> newCookies) {
        ProxySession session = getSession(userId);
        if (session == null) return;
        if (!CollectionUtils.isEmpty(newCookies)) {
            session.setCookiesJson(JSON.toJSONString(newCookies));
        }
        session.setLastUpdateTime(System.currentTimeMillis());
        session.setSchoolLoggedIn(false);
        saveSession(userId, session);
        log.info("userId={} 已登出学校后端（已更新Cookie）", userId);
    }

    /**
     * 判断是否已登录学校后端
     */
    public boolean isSchoolLoggedIn(String userId) {
        ProxySession session = getSession(userId);
        return session != null && session.isSchoolLoggedIn();
    }

    // ==================== Cookie 池刷新（供 CookieAccessEvent 使用） ====================

    /**
     * 按需刷新 Cookie 池
     * <p>
     * 5 分钟内不重复更新同一用户，避免每次请求都写 Redis。
     * 由 CookieAccessEventListener 异步调用。
     */
    private static final long REFRESH_THROTTLE_MS = 5 * 60 * 1000;

    public void refreshIfNeeded(String userId, String cookiesJson) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(cookiesJson)) return;
        ProxySession session = getSession(userId);
        if (session != null) {
            long elapsed = System.currentTimeMillis() - session.getLastUpdateTime();
            if (elapsed < REFRESH_THROTTLE_MS) return;
        }
        List<SmartCookie> cookies = SmartCookieConverter.jsonToSmartCookies(cookiesJson);
        saveOrUpdateSessionCookie(userId, cookies);
        log.info("刷新用户 {} 的 Cookie 池（来自用户请求）", userId);
    }

    // ==================== 清理 ====================

    /**
     * 清理单个用户的所有缓存
     */
    public void clearUser(String userId) {
        cacheUtil.del(PROXY_SESSION_PREFIX + userId);
        log.info("清理用户缓存: userId={}", userId);
    }

    /**
     * ⭐ 获取所有已登录的 ProxySession
     * <p>
     * 用于 CookieSourceManager 获取可用 Cookie。
     * 使用 KEYS 扫描（用户量 <1000 时可接受，量大后改 SCAN）。
     */
    public Map<String, ProxySession> getAllSessions() {
        Set<String> keys = cacheUtil.keys(PROXY_SESSION_PREFIX + "*");
        if (keys == null || keys.isEmpty()) return Collections.emptyMap();

        Map<String, ProxySession> result = new HashMap<>();
        for (String fullKey : keys) {
            // fullKey 经过 RedisKeyGenerator 处理后的完整 key
            // 需要提取 userId：去掉前缀
            String userId = extractUserIdFromKey(fullKey, "proxy-session:");
            if (userId == null) continue;

            ProxySession session = getSession(userId);
            if (session != null) {
                result.put(userId, session);
            }
        }
        return result;
    }


    // ==================== 内部 ====================

    private void saveSession(String userId, ProxySession session) {
        cacheUtil.set(PROXY_SESSION_PREFIX + userId, JSON.toJSONString(session), TTL_SECONDS);
    }

    /**
     * 从完整的 Redis key 中提取 userId
     * <p>
     * fullKey 格式（经过 RedisKeyGenerator）：dev:sztu:cache:icampus:token-meta:oXXXXX
     * 需要提取 oXXXXX 部分
     */
    private String extractUserIdFromKey(String fullKey, String marker) {
        int idx = fullKey.indexOf(marker);
        if (idx < 0) return null;
        return fullKey.substring(idx + marker.length());
    }
}