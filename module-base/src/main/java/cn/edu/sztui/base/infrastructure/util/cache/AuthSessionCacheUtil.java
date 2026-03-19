package cn.edu.sztui.base.infrastructure.util.cache;

import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.common.cache.dto.TokenMeta;
import cn.edu.sztui.common.cache.util.CacheUtil;
import cn.edu.sztui.common.util.enums.ResultCodeEnum;
import cn.edu.sztui.common.util.enums.SysReturnCode;
import cn.edu.sztui.common.util.exception.BusinessException;
import cn.edu.sztui.common.util.smarthttp.dto.SmartCookie;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 会话缓存工具（独立 Key 版）
 * <p>
 * ⭐ 改造：Hash → 独立 String Key + TTL
 * <p>
 * 存储结构：
 * <ul>
 *   <li>{@code icampus:token-meta:{openId}}    → String，value = TokenMeta JSON，TTL = 3天</li>
 *   <li>{@code icampus:proxy-session:{openId}}  → String，value = ProxySession JSON，TTL = 3天</li>
 * </ul>
 * <p>
 * 优势：
 * <ul>
 *   <li>每个用户独立 TTL，自动过期，不需要定时清理任务</li>
 *   <li>不存在大 Hash 阻塞（旧方案 1000 用户时 HGETALL 会卡住 Redis）</li>
 *   <li>touch() 刷新 TTL，实现滑动窗口</li>
 * </ul>
 */
@Slf4j
@Component
public class AuthSessionCacheUtil {

    /**
     * Key 前缀
     */
    private static final String TOKEN_META_PREFIX = "icampus:token-meta:";
    private static final String PROXY_SESSION_PREFIX = "icampus:proxy-session:";

    /**
     * TTL：3 天（秒）
     */
    private static final long TTL_SECONDS = 3 * 24 * 60 * 60;

    /**
     * 滑动窗口阈值：3 天（毫秒）—— 3天内活跃可续签 Token
     */
    public static final long SLIDING_WINDOW_MS = 3 * 24 * 60 * 60 * 1000L;

    @Resource
    private CacheUtil cacheUtil;

    // ==================== TokenMeta ====================

    /**
     * 创建或更新 TokenMeta
     */
    public void saveTokenMeta(TokenMeta meta) {
        cacheUtil.set(TOKEN_META_PREFIX + meta.getOpenId(), JSON.toJSONString(meta), TTL_SECONDS);
        log.info("保存 TokenMeta: openId={}", meta.getOpenId());
    }

    /**
     * 获取 TokenMeta
     */
    public TokenMeta getTokenMeta(String openId) {
        if (!StringUtils.hasText(openId)) return null;
        Object obj = cacheUtil.get(TOKEN_META_PREFIX + openId);
        if (obj == null) return null;
        return JSON.parseObject(obj.toString(), TokenMeta.class);
    }

    /**
     * 更新 lastAccessTime + 刷新 TTL（活跃续期）
     */
    public void touchTokenMeta(String openId) {
        TokenMeta meta = getTokenMeta(openId);
        if (meta == null) return;
        meta.setLastAccessTime(System.currentTimeMillis());
        // ⭐ 保存时自动刷新 TTL
        saveTokenMeta(meta);
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
        cacheUtil.del(TOKEN_META_PREFIX + openId);
    }

    // ==================== ProxySession ====================

    /**
     * 获取代理会话
     */
    public ProxySession getSession(String openId) {
        if (!StringUtils.hasText(openId)) return null;
        Object obj = cacheUtil.get(PROXY_SESSION_PREFIX + openId);
        if (obj == null) return null;
        return JSON.parseObject(obj.toString(), ProxySession.class);
    }

    /**
     * 判断代理会话是否存在
     */
    public boolean hasSession(String openId) {
        return cacheUtil.hasKey(PROXY_SESSION_PREFIX + openId);
    }

    /**
     * 删除代理会话（用于 initSession 强制重建）
     */
    public void deleteSession(String openId) {
        cacheUtil.del(PROXY_SESSION_PREFIX + openId);
        log.info("删除代理会话: openId={}", openId);
    }

    /**
     * 保存或更新 cookies
     */
    public boolean saveOrUpdateSessionCookie(String openId, List<SmartCookie> cookies) {
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
        session.setCookiesJson(JSON.toJSONString(cookies));
        session.setLastUpdateTime(System.currentTimeMillis());
        saveSession(openId, session);
        log.info("保存代理会话 cookie: openId={}", openId);
        return true;
    }

    /**
     * 登录绑定
     */
    public boolean sessionLoginBind(String openId, String userId, List<SmartCookie> newCookies) {
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
        session.setCookiesJson(JSON.toJSONString(newCookies));
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
     */
    public void sessionLogoutBind(String openId, List<SmartCookie> newCookies) {
        ProxySession session = getSession(openId);
        if (session == null) return;
        if (!CollectionUtils.isEmpty(newCookies)) {
            session.setCookiesJson(JSON.toJSONString(newCookies));
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
        cacheUtil.del(TOKEN_META_PREFIX + openId);
        cacheUtil.del(PROXY_SESSION_PREFIX + openId);
        log.info("清理用户缓存: openId={}", openId);
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
            // 需要提取 openId：去掉前缀
            String openId = extractOpenIdFromKey(fullKey, "proxy-session:");
            if (openId == null) continue;

            ProxySession session = getSession(openId);
            if (session != null) {
                result.put(openId, session);
            }
        }
        return result;
    }

    /**
     * ⭐ 获取所有 TokenMeta
     * <p>
     * 仅供兼容旧代码。独立 Key + TTL 后不再需要定时清理。
     */
    public Map<String, TokenMeta> getAllTokenMetas() {
        Set<String> keys = cacheUtil.keys(TOKEN_META_PREFIX + "*");
        if (keys == null || keys.isEmpty()) return Collections.emptyMap();

        Map<String, TokenMeta> result = new HashMap<>();
        for (String fullKey : keys) {
            String openId = extractOpenIdFromKey(fullKey, "token-meta:");
            if (openId == null) continue;

            TokenMeta meta = getTokenMeta(openId);
            if (meta != null) {
                result.put(openId, meta);
            }
        }
        return result;
    }

    /**
     * ⭐ 定时清理不再必要（Redis TTL 自动过期）
     * <p>
     * 保留此方法是为了兼容，但它现在基本是空操作。
     * 极端情况下（手动 set 无 TTL 的 key）仍可用。
     */
    public int cleanupStaleEntries() {
        // 独立 Key + TTL 后，Redis 自动清理过期数据
        // 此方法不再需要遍历全量数据
        log.debug("cleanupStaleEntries: 独立 Key + TTL 模式，Redis 自动过期，无需手动清理");
        return 0;
    }

    // ==================== 内部 ====================

    private void saveSession(String openId, ProxySession session) {
        cacheUtil.set(PROXY_SESSION_PREFIX + openId, JSON.toJSONString(session), TTL_SECONDS);
    }

    /**
     * 从完整的 Redis key 中提取 openId
     * <p>
     * fullKey 格式（经过 RedisKeyGenerator）：dev:sztu:cache:icampus:token-meta:oXXXXX
     * 需要提取 oXXXXX 部分
     */
    private String extractOpenIdFromKey(String fullKey, String marker) {
        int idx = fullKey.indexOf(marker);
        if (idx < 0) return null;
        return fullKey.substring(idx + marker.length());
    }
}