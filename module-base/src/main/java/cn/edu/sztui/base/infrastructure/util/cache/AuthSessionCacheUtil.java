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
     * <p>
     * ⚠️ 持久化前**过滤掉已过期的 cookie**。学校的 VWebServer 看到过期 cookie（比如过期的
     * `_idp_authn_lc_key`）会直接 414（Request-URI Too Large，非标）拒绝。从源头干净，
     * 比在发送链每层都过滤干净得多。2026-04-25 trace 对比 cookies-before.json 里确实
     * 看到 `expired=true` 的 cookie 被照样发出去。
     */
    public boolean saveOrUpdateSessionCookie(String userId, List<SmartCookie> cookies) {
        if (!StringUtils.hasText(userId) || CollectionUtils.isEmpty(cookies)) {
            return false;
        }
        List<SmartCookie> valid = cookies.stream()
                .filter(c -> !c.isExpired())
                .collect(java.util.stream.Collectors.toList());
        int dropped = cookies.size() - valid.size();
        if (dropped > 0) {
            log.info("saveOrUpdateSessionCookie: 过滤掉 {} 个过期 cookie, userId={}", dropped, userId);
        }
        if (valid.isEmpty()) {
            log.warn("saveOrUpdateSessionCookie: 过滤后 0 cookie，放弃写入 userId={}", userId);
            return false;
        }
        ProxySession session = getSession(userId);
        List<String> beforeNames = sessionCookieNames(session);
        if (session == null) {
            session = new ProxySession();
            session.setUserId(userId);
            session.setCreateTime(System.currentTimeMillis());
            session.setUserIds(new ArrayList<>());
            session.setSchoolLoggedIn(false);
        }
        session.setCookiesJson(JSON.toJSONString(valid));
        session.setLastUpdateTime(System.currentTimeMillis());
        saveSession(userId, session);
        logDelta("saveOrUpdate", userId, beforeNames, valid);
        return true;
    }

    /**
     * 登录绑定（不存在则创建，一个用户一个 session）
     */
    public boolean sessionLoginBind(String userId, String studentId, List<SmartCookie> newCookies) {
        ProxySession session = getSession(userId);
        List<String> beforeNames = sessionCookieNames(session);
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
        logDelta("loginBind", userId, beforeNames, newCookies);
        return true;
    }

    /**
     * 登出绑定：**清空 cookies** + 翻 schoolLoggedIn=false。
     * <p>
     * 为什么清：学校 logout 端点会让 TWFID / IDP SESSION 等关键 cookie 在学校服务端失效。
     * 如果 Redis 里保留旧 cookiesJson，下次 relogin 时，WebVPN 反代看到陈旧的 TWFID 会
     * 走"已登录"分支，跳过 re-issue TWFID 的步骤，导致登录返回的 cookie 里缺少 TWFID/
     * SESSION —— 进而 `/acdm/v1/refresh/cookies` 走 SSO 链被打回 /idp/AuthnEngine，
     * 课表/附件全挂。
     * <p>
     * 这是 2026-04-25 "退出重登立刻挂" 的根因（对照 trace 070728 成功 vs 070907 失败）。
     */
    public void sessionLogoutBind(String userId) {
        ProxySession session = getSession(userId);
        if (session == null) return;
        List<String> beforeNames = sessionCookieNames(session);
        session.setCookiesJson(null);
        session.setLastUpdateTime(System.currentTimeMillis());
        session.setSchoolLoggedIn(false);
        saveSession(userId, session);
        logDelta("logoutBind", userId, beforeNames, java.util.Collections.emptyList());
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
     * <p>
     * ⚠️ 反竞态：若本次请求携带的 cookie 数量 < Redis 里已有的，**拒绝覆盖**。
     * <p>
     * 根因场景：academic init 刚通过 SSO 链给 Redis 写了一份"丰满"的 cookie（含 TWFID /
     * IDP SESSION / 教务子域 JSESSIONID 等 6+ 条），紧接着一个并发的普通 HTTP 请求用
     * 前端 X-School-Cookies 里较"瘦"的 cookie（只有 4-5 条）触发了 CookieAccessEvent，
     * 如果让它无脑写，就会**缩减 Redis 里的 cookie**，覆盖掉刚建立好的完整会话，
     * 导致附件/课表瞬间挂。2026-04-25 trace 对比实证了这一点。
     * <p>
     * 防御：拒绝"瘦 cookie 覆盖肥 cookie"。前端的 cookie 本来就是副本，没必要回写 Redis；
     * 真正权威的 cookie 由 auth 流程（login / refresh / academic init）显式调
     * saveOrUpdateSessionCookie 写入。CookieAccessEvent 只负责"兜底增量"，不负责"替换"。
     */
    private static final long REFRESH_THROTTLE_MS = 5 * 60 * 1000;

    public void refreshIfNeeded(String userId, String cookiesJson) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(cookiesJson)) return;
        ProxySession session = getSession(userId);
        if (session != null) {
            // **硬规则**：用户已登出 / session 标记 schoolLoggedIn=false 后，所有靠 user
            // 顺手 HTTP 请求触发的 cookie pool 兜底刷新都必须停。否则登出后零星请求会把
            // cookies 复活回 Redis，违反"登出 = 立刻不能继续被借 cookies"的约定。
            if (!session.isSchoolLoggedIn()) {
                log.debug("refreshIfNeeded 跳过: userId={} 已登出", userId);
                return;
            }
            long elapsed = System.currentTimeMillis() - session.getLastUpdateTime();
            if (elapsed < REFRESH_THROTTLE_MS) return;

            // 反竞态：Redis 现有 cookie 数 > 请求 header 里的，说明请求携带的是"瘦"快照，
            // 多半是前端 WS COOKIE_UPDATE 尚未落地的陈旧副本，或 multi-tab 不同步。不覆盖。
            String existingJson = session.getCookiesJson();
            if (StringUtils.hasText(existingJson)) {
                int existingCount = countCookies(existingJson);
                int incomingCount = countCookies(cookiesJson);
                if (existingCount > incomingCount) {
                    log.info("refreshIfNeeded 跳过缩减: userId={} redis={}条 incoming={}条",
                            userId, existingCount, incomingCount);
                    return;
                }
            }
        }
        List<SmartCookie> cookies = SmartCookieConverter.jsonToSmartCookies(cookiesJson);
        saveOrUpdateSessionCookie(userId, cookies);
        log.info("刷新用户 {} 的 Cookie 池（来自用户请求）", userId);
    }

    /** 粗略数一下 cookies JSON 数组里有多少个 cookie（忽略结构细节，只用作缩减判定）。 */
    private static int countCookies(String cookiesJson) {
        try {
            return SmartCookieConverter.jsonToSmartCookies(cookiesJson).size();
        } catch (Exception e) {
            return 0;
        }
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
     * 抽取 ProxySession 当前 cookie 列表的 name+domain 概要，用于 delta log。
     * 每条形如 "TWFID@webvpn.sztu.edu.cn"。
     */
    private static List<String> sessionCookieNames(ProxySession session) {
        if (session == null || !StringUtils.hasText(session.getCookiesJson())) {
            return java.util.Collections.emptyList();
        }
        try {
            List<SmartCookie> cs = JSON.parseArray(session.getCookiesJson(), SmartCookie.class);
            return cs.stream()
                    .map(c -> c.getName() + "@" + (c.getDomain() == null ? "?" : c.getDomain()))
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    private static List<String> cookieNames(List<SmartCookie> cookies) {
        if (cookies == null) return java.util.Collections.emptyList();
        return cookies.stream()
                .map(c -> c.getName() + "@" + (c.getDomain() == null ? "?" : c.getDomain()))
                .sorted()
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 打印 ProxySession cookie 集合的前后差分。
     * 输出形式（grep "ProxySession Δ" 得完整时间线）：
     * <pre>
     * [ProxySession Δ] op=loginBind userId=202200202104 5→6 +[JSESSIONID@jwxt-...] -[]
     * </pre>
     */
    private static void logDelta(String op, String userId, List<String> before, List<SmartCookie> after) {
        List<String> afterNames = cookieNames(after);
        java.util.Set<String> bSet = new java.util.LinkedHashSet<>(before);
        java.util.Set<String> aSet = new java.util.LinkedHashSet<>(afterNames);
        List<String> added = new java.util.ArrayList<>(aSet);
        added.removeAll(bSet);
        List<String> removed = new java.util.ArrayList<>(bSet);
        removed.removeAll(aSet);
        log.info("[ProxySession Δ] op={} userId={} {}→{} +{} -{}",
                op, userId, before.size(), afterNames.size(), added, removed);
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