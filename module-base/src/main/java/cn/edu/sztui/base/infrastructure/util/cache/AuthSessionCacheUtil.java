package cn.edu.sztui.base.infrastructure.util.cache;

import cn.edu.sztui.base.application.vo.LoginStatusVo;
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
 * 会话缓存工具（重构版）
 * <p>
 * 存储结构（适配 CacheUtil 的 Hash API）：
 * <ul>
 *   <li>{@code icampus:token-meta}      → Hash，field = openId，value = TokenMeta JSON</li>
 *   <li>{@code icampus:proxy-session}   → Hash，field = openId，value = ProxySession JSON</li>
 *   <li>{@code icampus:login-status}    → Hash，field = openId，value = LoginStatusVo JSON（短TTL缓存）</li>
 * </ul>
 * <p>
 * <b>新增功能</b>：
 * <ul>
 *   <li>登录状态缓存（30秒TTL，减少 Playwright 调用）</li>
 *   <li>Cookie 过期检查（保守策略，可配置）</li>
 * </ul>
 */
@Slf4j
@Component
public class AuthSessionCacheUtil {

    /** TokenMeta 的 Hash key */
    private static final String TOKEN_META_KEY = "icampus:token-meta";
    /** ProxySession 的 Hash key */
    private static final String PROXY_SESSION_KEY = "icampus:proxy-session";
    /** LoginStatus 缓存的 Hash key */
    private static final String LOGIN_STATUS_KEY = "icampus:login-status";

    /** 滑动窗口阈值：24 小时（毫秒） */
    public static final long SLIDING_WINDOW_MS = 24 * 60 * 60 * 1000L;

    /** 清理阈值：25 小时（毫秒），比滑动窗口多 1h 缓冲 */
    public static final long CLEANUP_THRESHOLD_MS = 25 * 60 * 60 * 1000L;

    /** 状态缓存有效期：30 秒（毫秒） */
    public static final long STATUS_CACHE_TTL_MS = 30 * 1000L;

    /** Cookie 保守过期时间：2 小时（毫秒）—— 超过此时间未刷新则视为可能过期 */
    public static final long COOKIE_CONSERVATIVE_EXPIRE_MS = 2 * 60 * 60 * 1000L;

    /** Cookie 即将过期预警：30 分钟（毫秒） */
    public static final long COOKIE_EXPIRING_SOON_MS = 30 * 60 * 1000L;

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
     * 判断是否在滑动窗口内（< 24h），允许刷新 token
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
            throw new BusinessException(SysReturnCode.BASE_PROXY.getCode(),"无法获取代理会话，请先初始化",ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode());
        }
        if (!session.getUserIds().contains(userId))
            session.getUserIds().add(userId);
        session.setCookiesJson(CookieConverter.toCookieStrings(newCookies));
        session.setLastUpdateTime(System.currentTimeMillis());
        session.setSchoolLoggedIn(true);
        saveSession(openId, session);
        log.info("用户 {} 绑定到 openId={}", userId, openId);
        return true;
    }

    /**
     * 登出
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
     * 判断是否已登录学校后端
     */
    public boolean isSchoolLoggedIn(String openId) {
        ProxySession session = getSession(openId);
        return session != null && session.isSchoolLoggedIn();
    }

    // ==================== 状态缓存（新增） ====================

    /**
     * 获取缓存的登录状态
     *
     * @return LoginStatusVo 或 null（缓存不存在或已过期）
     */
    public LoginStatusVo getCachedStatus(String openId) {
        if (!StringUtils.hasText(openId)) return null;
        Object obj = cacheUtil.hget(LOGIN_STATUS_KEY, openId);
        if (obj == null) return null;

        LoginStatusVo status = JSON.parseObject(obj.toString(), LoginStatusVo.class);

        // 检查缓存是否过期（应用层 TTL）
        if (status.getStatusTime() == null) return null;
        long elapsed = System.currentTimeMillis() - status.getStatusTime();
        if (elapsed > STATUS_CACHE_TTL_MS) {
            // 缓存过期，删除并返回 null
            cacheUtil.hdel(LOGIN_STATUS_KEY, openId);
            return null;
        }
        return status;
    }

    /**
     * 缓存登录状态
     */
    public void cacheStatus(String openId, LoginStatusVo status) {
        if (!StringUtils.hasText(openId) || status == null) return;
        status.setStatusTime(System.currentTimeMillis());
        cacheUtil.hset(LOGIN_STATUS_KEY, openId, JSON.toJSONString(status));
        log.debug("缓存登录状态: openId={}, logined={}", openId, status.isLogined());
    }

    /**
     * 使状态缓存失效
     */
    public void invalidateStatusCache(String openId) {
        cacheUtil.hdel(LOGIN_STATUS_KEY, openId);
        log.debug("状态缓存已失效: openId={}", openId);
    }

    // ==================== Cookie 过期检查（新增） ====================

    /**
     * 检查 Cookie 是否可能已过期（保守策略）
     * <p>
     * 由于学校返回的 Cookie 过期时间不确定，采用保守策略：
     * 如果 lastUpdateTime 超过 COOKIE_CONSERVATIVE_EXPIRE_MS，则认为可能过期
     *
     * @return true 表示 Cookie 可能已过期，建议重新初始化
     */
    public boolean isCookiePossiblyExpired(String openId) {
        ProxySession session = getSession(openId);
        if (session == null) return true;
        if ( Objects.isNull(session.getLastUpdateTime()) ) return true;

        long elapsed = System.currentTimeMillis() - session.getLastUpdateTime();
        return elapsed > COOKIE_CONSERVATIVE_EXPIRE_MS;
    }

    /**
     * 检查 Cookie 是否即将过期（用于提前刷新提醒）
     *
     * @return true 表示 Cookie 即将过期（距离保守过期时间不足 30 分钟）
     */
    public boolean isCookieExpiringSoon(String openId) {
        ProxySession session = getSession(openId);
        if (session == null) return true;
        if ( Objects.isNull(session.getLastUpdateTime()) ) return true;

        long elapsed = System.currentTimeMillis() - session.getLastUpdateTime();
        long remaining = COOKIE_CONSERVATIVE_EXPIRE_MS - elapsed;
        return remaining < COOKIE_EXPIRING_SOON_MS;
    }

    /**
     * 清除会话的 Cookie（保留其他信息）
     * <p>
     * 用于 Cookie 过期后强制重新初始化
     */
    public void clearSessionCookies(String openId) {
        ProxySession session = getSession(openId);
        if (session == null) return;
        session.setCookiesJson("");
        session.setSchoolLoggedIn(false);
        session.setLastUpdateTime(System.currentTimeMillis());
        saveSession(openId, session);
        log.info("已清除 openId={} 的 Cookie", openId);
    }

    // ==================== 清理 ====================

    /**
     * 清理单个用户的所有缓存
     */
    public void clearUser(String openId) {
        cacheUtil.hdel(TOKEN_META_KEY, openId);
        cacheUtil.hdel(PROXY_SESSION_KEY, openId);
        cacheUtil.hdel(LOGIN_STATUS_KEY, openId);
        log.info("清理用户缓存: openId={}", openId);
    }

    /**
     * 清理过期会话（由定时任务调用）
     * <p>
     * 遍历所有 TokenMeta，删除 lastAccessTime > 25h 的条目及其关联的 ProxySession。
     *
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
                clearUser(openId);
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