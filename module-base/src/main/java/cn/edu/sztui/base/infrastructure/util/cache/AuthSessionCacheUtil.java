package cn.edu.sztui.base.infrastructure.util.cache;

import cn.edu.sztui.base.infrastructure.convertor.CookieConverter;
import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.common.cache.dto.TokenMeta;
import cn.edu.sztui.common.cache.util.CacheUtil;
import cn.edu.sztui.common.util.enums.ResultCodeEnum;
import cn.edu.sztui.common.util.enums.SysReturnCode;
import cn.edu.sztui.common.util.exception.BusinessException;
import com.alibaba.fastjson2.JSON;
import com.microsoft.playwright.options.Cookie;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 会话缓存工具（精简版）
 * <p>
 * 存储结构（适配 CacheUtil 的 Hash API）：
 * <ul>
 *   <li>{@code icampus:token-meta}      → Hash，field = openId，value = TokenMeta JSON</li>
 *   <li>{@code icampus:proxy-session}   → Hash，field = openId，value = ProxySession JSON</li>
 * </ul>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>Cookie 跟随 Token 生命周期，Token 过期时一并清除</li>
 *   <li>不做 Cookie 过期预测，由学校重定向自然触发 403</li>
 *   <li>不做状态缓存，每次 getStatus 都实时检查</li>
 * </ul>
 */
@Slf4j
@Component
public class AuthSessionCacheUtil {

    /**
     * TokenMeta 的 Hash key
     */
    private static final String TOKEN_META_KEY = "icampus:token-meta";

    /**
     * ProxySession 的 Hash key
     */
    private static final String PROXY_SESSION_KEY = "icampus:proxy-session";

    /**
     * 滑动窗口阈值：3 天（毫秒）—— 3天内活跃可续签 Token
     */
    public static final long SLIDING_WINDOW_MS = 3 * 24 * 60 * 60 * 1000L;

    /**
     * 清理阈值：3 天 + 1 小时（毫秒）—— 超过此时间的会话会被清理
     */
    public static final long CLEANUP_THRESHOLD_MS = (3 * 24 + 1) * 60 * 60 * 1000L;

    @Resource
    private CacheUtil cacheUtil;

    // ==================== TokenMeta ====================

    /**
     * 创建或更新 TokenMeta
     */
    public void saveTokenMeta(TokenMeta meta) {
        cacheUtil.hset(TOKEN_META_KEY, meta.getOpenId(), JSON.toJSONString(meta));
        log.info("保存 TokenMeta: openId={}", meta.getOpenId());
    }

    /**
     * 获取 TokenMeta
     */
    public TokenMeta getTokenMeta(String openId) {
        if (!StringUtils.hasText(openId)) return null;
        Object obj = cacheUtil.hget(TOKEN_META_KEY, openId);
        if (obj == null) return null;
        return JSON.parseObject(obj.toString(), TokenMeta.class);
    }

    /**
     * 更新 lastAccessTime（活跃续期）
     */
    public void touchTokenMeta(String openId) {
        TokenMeta meta = getTokenMeta(openId);
        if (meta == null) return;
        meta.setLastAccessTime(System.currentTimeMillis());
        cacheUtil.hset(TOKEN_META_KEY, openId, JSON.toJSONString(meta));
    }

    /**
     * 判断是否在滑动窗口内（< 3天），允许刷新 token
     */
    public boolean isRefreshable(String openId) {
        TokenMeta meta = getTokenMeta(openId);
        if (meta == null) return false;
        long elapsed = System.currentTimeMillis() - meta.getLastAccessTime();
        return elapsed < SLIDING_WINDOW_MS;
    }

    /**
     * 删除 TokenMeta
     */
    public void deleteTokenMeta(String openId) {
        cacheUtil.hdel(TOKEN_META_KEY, openId);
    }

    /**
     * 获取所有 TokenMeta（供定时任务清理用）
     */
    public Map<String, TokenMeta> getAllTokenMetas() {
        Map<Object, Object> rawMap = cacheUtil.hmget(TOKEN_META_KEY);
        if (rawMap == null || rawMap.isEmpty()) return Collections.emptyMap();
        Map<String, TokenMeta> result = new HashMap<>();
        rawMap.forEach((key, value) -> {
            result.put(key.toString(), JSON.parseObject(value.toString(), TokenMeta.class));
        });
        return result;
    }

    // ==================== ProxySession ====================

    /**
     * 获取代理会话
     */
    public ProxySession getSession(String openId) {
        if (!StringUtils.hasText(openId)) return null;
        Object obj = cacheUtil.hget(PROXY_SESSION_KEY, openId);
        if (obj == null) return null;
        return JSON.parseObject(obj.toString(), ProxySession.class);
    }

    /**
     * 判断代理会话是否存在
     */
    public boolean hasSession(String openId) {
        return cacheUtil.hHasKey(PROXY_SESSION_KEY, openId);
    }

    /**
     * 删除代理会话（用于 initSession 强制重建）
     */
    public void deleteSession(String openId) {
        cacheUtil.hdel(PROXY_SESSION_KEY, openId);
        log.info("删除代理会话: openId={}", openId);
    }

    /**
     * 获取所有代理会话（供定时任务批量操作用）
     */
    public Map<String, ProxySession> getAllSessions() {
        Map<Object, Object> rawMap = cacheUtil.hmget(PROXY_SESSION_KEY);
        if (rawMap == null || rawMap.isEmpty()) return Collections.emptyMap();
        Map<String, ProxySession> result = new HashMap<>();
        rawMap.forEach((key, value) -> {
            result.put(key.toString(), JSON.parseObject(value.toString(), ProxySession.class));
        });
        return result;
    }

    /**
     * 保存或更新 cookies
     */
    public boolean saveOrUpdateSessionCookie(String openId, List<Cookie> cookies) {
        if (!StringUtils.hasText(openId) || CollectionUtils.isEmpty(cookies)) {
            return false;
        }
        ProxySession session = getSession(openId);
        if (session == null) {
            session = new ProxySession();
            session.setOpenId(openId);
            session.setCreateTime(System.currentTimeMillis());
            session.setUserIds(new ArrayList<>());
            session.setSchoolLoggedIn(false);
        }
        session.setCookiesJson(CookieConverter.toCookieStrings(cookies));
        session.setLastUpdateTime(System.currentTimeMillis());
        saveSession(openId, session);
        log.info("保存代理会话 cookie: openId={}", openId);
        return true;
    }

    /**
     * 登录绑定
     */
    public boolean sessionLoginBind(String openId, String userId, List<Cookie> newCookies) {
        ProxySession session = getSession(openId);
        if (session == null) {
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "无法获取代理会话，请先初始化",
                    ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
            );
        }
        if (!session.getUserIds().contains(userId)) {
            session.getUserIds().add(userId);
        }
        session.setCookiesJson(CookieConverter.toCookieStrings(newCookies));
        session.setLastUpdateTime(System.currentTimeMillis());
        session.setSchoolLoggedIn(true);
        saveSession(openId, session);
        log.info("用户 {} 绑定到 openId={}", userId, openId);
        return true;
    }

    /**
     * 登出绑定（保留 Cookie，只更新状态）
     */
    public void sessionLogoutBind(String openId) {
        ProxySession session = getSession(openId);
        if (session == null) return;
        session.setLastUpdateTime(System.currentTimeMillis());
        session.setSchoolLoggedIn(false);
        saveSession(openId, session);
        log.info("openId={} 已登出学校后端", openId);
    }

    /**
     * 登出绑定（更新 Cookie 和状态）
     * <p>
     * 用于登出时保存学校返回的新 Cookie
     */
    public void sessionLogoutBind(String openId, List<Cookie> newCookies) {
        ProxySession session = getSession(openId);
        if (session == null) return;

        // 更新 Cookie
        if (!CollectionUtils.isEmpty(newCookies)) {
            session.setCookiesJson(CookieConverter.toCookieStrings(newCookies));
        }

        session.setLastUpdateTime(System.currentTimeMillis());
        session.setSchoolLoggedIn(false);
        saveSession(openId, session);
        log.info("openId={} 已登出学校后端（已更新Cookie）", openId);
    }

    /**
     * 判断是否已登录学校后端
     */
    public boolean isSchoolLoggedIn(String openId) {
        ProxySession session = getSession(openId);
        return session != null && session.isSchoolLoggedIn();
    }

    // ==================== 清理 ====================

    /**
     * 清理单个用户的所有缓存
     */
    public void clearUser(String openId) {
        cacheUtil.hdel(TOKEN_META_KEY, openId);
        cacheUtil.hdel(PROXY_SESSION_KEY, openId);
        log.info("清理用户缓存: openId={}", openId);
    }

    /**
     * 清理过期会话（由定时任务调用）
     * <p>
     * 遍历所有 TokenMeta，删除 lastAccessTime > 3天+1小时 的条目及其关联的 ProxySession。
     * <p>
     * 关键：Token 过期时同时清除 Cookie，保证两者生命周期一致
     * @return 清理的条目数
     */
    public int cleanupStaleEntries() {
        Map<String, TokenMeta> allMetas = getAllTokenMetas();
        long now = System.currentTimeMillis();
        int cleaned = 0;

        for (Map.Entry<String, TokenMeta> entry : allMetas.entrySet()) {
            String openId = entry.getKey();
            TokenMeta meta = entry.getValue();
            long elapsed = now - meta.getLastAccessTime();

            if (elapsed > CLEANUP_THRESHOLD_MS) {
                clearUser(openId);  // 同时删除 TokenMeta 和 ProxySession
                cleaned++;
                log.info("清理过期会话: openId={}, 已不活跃 {}h",
                        openId, elapsed / (1000 * 60 * 60));
            }
        }
        return cleaned;
    }

    // ==================== 内部 ====================

    private void saveSession(String openId, ProxySession session) {
        cacheUtil.hset(PROXY_SESSION_KEY, openId, JSON.toJSONString(session));
    }
}