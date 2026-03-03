package cn.edu.sztui.stream.application.external;

import cn.edu.sztui.base.infrastructure.util.cache.AnnouncementCacheUtil;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.common.cache.dto.ProxySession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import jakarta.annotation.Resource;

import java.util.Map;

/**
 * 抽象爬取任务基类
 */
@Slf4j
public abstract class AbstractCrawlTask {

    @Resource
    protected AnnouncementCacheUtil announcementCacheUtil;

    @Resource
    protected AuthSessionCacheUtil authSessionCacheUtil;

    protected void execute() {
        String sourceOpenId = getAvailableCookieSource();

        if (sourceOpenId == null) {
            log.debug("无可用的 Cookie 来源，跳过本次爬取");
            return;
        }

        try {
            doCrawl(sourceOpenId);
        } catch (Exception e) {
            log.error("爬取任务执行失败", e);
            if (isAuthError(e)) {
                log.warn("Cookie 来源 {} 认证失败，尝试切换", sourceOpenId);
                markSourceInvalidAndSwitch(sourceOpenId);
            }
        }
    }

    protected String getAvailableCookieSource() {
        String activeSource = announcementCacheUtil.getActiveSourceOpenId();

        if (StringUtils.hasText(activeSource) && isSourceValid(activeSource)) {
            return activeSource;
        }

        String newSource = findValidSourceFromOnlineUsers();
        if (newSource != null) {
            announcementCacheUtil.setActiveSourceOpenId(newSource);
            log.info("切换到新的 Cookie 来源: {}", newSource);
            return newSource;
        }

        announcementCacheUtil.clearActiveSource();
        return null;
    }

    protected boolean isSourceValid(String openId) {
        if (!StringUtils.hasText(openId)) return false;
        if (!authSessionCacheUtil.hasSession(openId)) return false;
        if (!authSessionCacheUtil.isSchoolLoggedIn(openId)) return false;
        return !authSessionCacheUtil.isCookiePossiblyExpired(openId);
    }

    protected String findValidSourceFromOnlineUsers() {
        Map<String, ProxySession> allSessions = authSessionCacheUtil.getAllSessions();
        if (allSessions == null || allSessions.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, ProxySession> entry : allSessions.entrySet()) {
            String openId = entry.getKey();
            if (entry.getValue().isSchoolLoggedIn() && isSourceValid(openId)) {
                return openId;
            }
        }
        return null;
    }

    protected void markSourceInvalidAndSwitch(String invalidOpenId) {
        announcementCacheUtil.clearActiveSource();
        String newSource = findValidSourceFromOnlineUsers();
        if (newSource != null && !newSource.equals(invalidOpenId)) {
            announcementCacheUtil.setActiveSourceOpenId(newSource);
        }
    }

    protected boolean isAuthError(Exception e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("401") || msg.contains("认证") || msg.contains("登录"));
    }

    protected abstract void doCrawl(String sourceOpenId);
}