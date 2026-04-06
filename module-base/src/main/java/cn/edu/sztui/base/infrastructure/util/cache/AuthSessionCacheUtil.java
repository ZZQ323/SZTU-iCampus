package cn.edu.sztui.base.infrastructure.util.cache;

import cn.edu.sztui.common.cache.dto.ProxySession;
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
 * 会话缓存工具
 * <p>
 * 存储结构：
 * <ul>
 *   <li>{@code icampus:proxy-session:{openId}}  → String，value = ProxySession JSON，TTL = 3天</li>
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