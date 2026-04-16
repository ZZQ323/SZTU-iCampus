package cn.edu.sztui.stream.application.external.engine;

import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfigLoader;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import cn.edu.sztui.stream.infrastructure.websocket.registry.WsSessionRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 公文通快速轮询调度器
 * <p>
 * 公文通（需要登录的 announcement 频道）单独用 10 秒 tick，
 * 比普通源的 60 秒 tick 更频繁，确保用户能及时看到新公告。
 * <p>
 * ⭐ 仅在有 WS 在线用户时轮询，避免使用离线用户 cookie 导致其有效期被意外延长。
 */
@Slf4j
@Component
public class AnnouncementFastScheduler {

    @Resource
    private CrawlEngine crawlEngine;

    @Resource
    private CrawlerConfigLoader configLoader;

    @Resource
    private InfoCacheUtil infoCacheUtil;

    @Resource
    private CookieSourceManager cookieSourceManager;

    @Resource
    private WsSessionRegistry wsSessionRegistry;

    @PostConstruct
    public void init() {
        log.info("AnnouncementFastScheduler 已初始化（10s tick）");
    }

    /**
     * 每 10 秒检查公文通是否需要爬取
     * <p>
     * ⭐ 前置条件：必须有 WS 在线用户（证明有人在用小程序），
     * 不使用离线用户的 cookie 轮询，避免意外延长 cookie 有效期。
     */
    @Scheduled(fixedRate = 10000, initialDelay = 30000)
    public void tickAnnouncement() {
        // ⭐ 必须有 WS 在线用户才轮询
        if (wsSessionRegistry.getOnlineUserIds().isEmpty()) {
            log.debug("公文通快速轮询: 无在线用户，跳过");
            return;
        }

        // 无可用 Cookie → 跳过
        boolean hasCookie = cookieSourceManager.hasAvailableCookie();
        if (!hasCookie) {
            log.debug("公文通快速轮询: 无可用 Cookie，跳过");
            return;
        }

        log.info("公文通快速轮询: 开始检查（有 Cookie）");

        List<CrawlerConfig.SourceConfig> gwtSources = configLoader.getEnabledSources().stream()
                .filter(s -> "announcement".equals(s.getChannelId()))
                .toList();

        for (CrawlerConfig.SourceConfig source : gwtSources) {
            if (!shouldCrawlNow(source)) {
                continue;
            }

            try {
                CrawlResult result = crawlEngine.crawlIncremental(source.getId());
                if (result.isAuthError()) {
                    cookieSourceManager.markInvalidAndSwitch(result.getCookieUserId());
                    log.warn("公文通 Cookie 失效: source={}, userId={}", source.getId(), result.getCookieUserId());
                    return; // Cookie 失效，停止本轮爬取
                } else if (result.isSuccess() && result.getNewCount() > 0) {
                    log.info("公文通快速爬取: {} → {} 条新内容", source.getId(), result.getNewCount());
                }
            } catch (Exception e) {
                log.error("公文通快速爬取异常: source={}, error={}", source.getId(), e.getMessage());
            }
        }
    }

    private boolean shouldCrawlNow(CrawlerConfig.SourceConfig source) {
        int intervalMinutes = source.getCrawlIntervalMinutes();
        if (intervalMinutes <= 0) return false;

        Long lastCrawl = infoCacheUtil.getLastCrawlTime(source.getId());
        if (lastCrawl == null) return true;

        long intervalMs = intervalMinutes * 60 * 1000L;
        return System.currentTimeMillis() - lastCrawl > intervalMs;
    }
}
