package cn.edu.sztui.stream.infrastructure.cookie;

import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.common.cache.dto.ProxySession;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cookie 来源管理器
 * <p>
 * 统一管理系统级 Cookie 来源，用于公告、活动等公共数据的爬取
 * <p>
 * 设计原则：
 * <ul>
 *   <li>第一个登录成功的用户成为 Cookie 来源</li>
 *   <li>Cookie 失效时自动切换到其他在线用户</li>
 *   <li>无可用 Cookie 时暂停相关功能</li>
 * </ul>
 */
@Slf4j
@Component
public class CookieSourceManager {

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    /** 当前 Cookie 来源的 openId */
    private final AtomicReference<String> activeSourceOpenId = new AtomicReference<>(null);

    /** 系统是否已初始化（至少有一个用户登录过） */
    @Getter
    private final AtomicBoolean systemInitialized = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        // 启动时尝试从现有会话中恢复状态
        String existingSource = findAvailableCookieSource();
        if (existingSource != null) {
            activeSourceOpenId.set(existingSource);
            systemInitialized.set(true);
            log.info("系统启动，恢复 Cookie 来源: {}", maskOpenId(existingSource));
        } else {
            log.info("系统启动，等待第一个用户登录...");
        }
    }

    /**
     * 用户登录成功后调用
     * <p>
     * 如果是第一个登录的用户，将其设为系统 Cookie 来源
     *
     * @param openId 用户的微信 openId
     */
    public void onUserLogin(String openId) {
        if (!systemInitialized.get()) {
            // 第一个登录的用户，初始化系统
            activeSourceOpenId.set(openId);
            systemInitialized.set(true);
            log.info("系统初始化完成，首个 Cookie 来源: {}", maskOpenId(openId));
        } else if (activeSourceOpenId.get() == null) {
            // 之前的来源失效了，使用新登录的用户
            activeSourceOpenId.set(openId);
            log.info("Cookie 来源已恢复: {}", maskOpenId(openId));
        } else {
            log.debug("用户 {} 登录，当前 Cookie 来源为: {}",
                    maskOpenId(openId), maskOpenId(activeSourceOpenId.get()));
        }
    }

    /**
     * 检查并获取可用的 Cookie 来源
     *
     * @return 可用的 openId，如果没有返回 null
     */
    public String getAvailableSource() {
        String current = activeSourceOpenId.get();

        // 检查当前来源是否有效
        if (current != null && isSourceValid(current)) {
            return current;
        }

        // 当前来源无效，尝试切换
        log.warn("当前 Cookie 来源 {} 无效，尝试切换...", maskOpenId(current));
        String newSource = findAvailableCookieSource();

        if (newSource != null) {
            activeSourceOpenId.set(newSource);
            log.info("Cookie 来源已切换为: {}", maskOpenId(newSource));
            return newSource;
        } else {
            activeSourceOpenId.set(null);
            log.warn("无可用的 Cookie 来源，系统功能受限");
            return null;
        }
    }

    /**
     * 检查系统是否可运行
     * <p>
     * 系统可运行的条件：已初始化 且 有可用的 Cookie 来源
     *
     * @return true 表示系统可以执行爬取任务
     */
    public boolean isSystemOperational() {
        if (!systemInitialized.get()) {
            return false;
        }
        return getAvailableSource() != null;
    }

    /**
     * 标记当前来源失效
     * <p>
     * 当爬取失败时调用，触发来源切换
     */
    public void markSourceInvalid() {
        String current = activeSourceOpenId.get();
        if (current != null) {
            log.warn("标记 Cookie 来源 {} 为无效", maskOpenId(current));
            // 下次 getAvailableSource() 调用时会触发切换
        }
    }

    /**
     * 获取当前状态信息
     */
    public CookieSourceStatus getStatus() {
        return new CookieSourceStatus(
                systemInitialized.get(),
                activeSourceOpenId.get(),
                isSystemOperational()
        );
    }

    // ==================== 内部方法 ====================

    /**
     * 检查指定来源是否有效
     */
    private boolean isSourceValid(String openId) {
        if (openId == null) return false;

        // 检查是否已登录学校
        if (!authSessionCacheUtil.isSchoolLoggedIn(openId)) {
            return false;
        }

        // 检查 Cookie 是否可能过期
        if (authSessionCacheUtil.isCookiePossiblyExpired(openId)) {
            return false;
        }

        return true;
    }

    /**
     * 从所有会话中查找可用的 Cookie 来源
     */
    private String findAvailableCookieSource() {
        Map<String, ProxySession> allSessions = authSessionCacheUtil.getAllSessions();

        for (Map.Entry<String, ProxySession> entry : allSessions.entrySet()) {
            String openId = entry.getKey();
            ProxySession session = entry.getValue();

            // 检查会话是否有效
            if (session.isSchoolLoggedIn() &&
                    !authSessionCacheUtil.isCookiePossiblyExpired(openId)) {
                return openId;
            }
        }
        return null;
    }

    /**
     * 脱敏处理 openId（日志安全）
     */
    private String maskOpenId(String openId) {
        if (openId == null) return "null";
        if (openId.length() <= 8) return "***";
        return openId.substring(0, 4) + "***" + openId.substring(openId.length() - 4);
    }

    // ==================== 状态类 ====================

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class CookieSourceStatus {
        /** 系统是否已初始化 */
        private boolean initialized;
        /** 当前活跃的 Cookie 来源 openId（脱敏前） */
        private String activeOpenId;
        /** 系统是否可运行 */
        private boolean operational;
    }
}