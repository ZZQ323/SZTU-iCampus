package cn.edu.sztui.stream.application.external.engine;

import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfigLoader;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据源定时爬取调度器
 * <p>
 * 替代原来每个源一个 CrawlTask 的做法。
 * 每分钟 tick 一次，遍历所有 enabled 的源，判断是否到了该爬的时间。
 * <p>
 * 新增源不需要写新的定时任务，只需要在 sources.yml 中配置 crawlIntervalMinutes。
 * <p>
 * 文件位置：module-stream/.../application/external/engine/SourceCrawlScheduler.java
 */
@Slf4j
@Component
@EnableScheduling
public class SourceCrawlScheduler {

    @Resource
    private CrawlEngine crawlEngine;

    @Resource
    private CrawlerConfigLoader configLoader;

    @Resource
    private InfoCacheUtil infoCacheUtil;

    @Resource
    private CookieSourceManager cookieSourceManager;

    /**
     * 每 60 秒检查一次哪些源需要爬取
     * <p>
     * 比起给每个源写一个 @Scheduled，这种方式：
     * - 新增源只改 YAML，不改代码
     * - 每个源的爬取间隔可以不同（10分钟、30分钟、60分钟）
     * - 统一的错误处理和日志
     */
    @Scheduled(fixedRate = 60000, initialDelay = 120000)
    public void tick() {
        List<CrawlerConfig.SourceConfig> sources = configLoader.getEnabledSources();

        if (sources.isEmpty()) {
            return;
        }

        int crawledCount = 0;
        int skippedCount = 0;

        for (CrawlerConfig.SourceConfig source : sources) {
            if (!shouldCrawlNow(source)) {
                skippedCount++;
                continue;
            }

            // 需要登录的源，检查是否有可用 Cookie
            if (source.isRequiresAuth() && !cookieSourceManager.hasAvailableCookie()) {
                log.debug("跳过需登录源（无 Cookie）: {}", source.getId());
                skippedCount++;
                continue;
            }

            try {
                CrawlResult result = crawlEngine.crawlIncremental(source.getId());
                if (result.isAuthError()) {
                    cookieSourceManager.markInvalidAndSwitch(result.getCookieUserId());
                    log.warn("定时爬取 Cookie 失效: source={}, userId={}", source.getId(), result.getCookieUserId());
                } else if (result.isSuccess() && result.getNewCount() > 0) {
                    log.info("定时爬取: {} → {} 条新内容", source.getId(), result.getNewCount());
                }
                crawledCount++;
            } catch (Exception e) {
                log.error("定时爬取异常: source={}, error={}", source.getId(), e.getMessage());
            }
        }

        if (crawledCount > 0) {
            log.debug("定时爬取完成: 爬取 {} 个源, 跳过 {} 个", crawledCount, skippedCount);
        }
    }

    /**
     * 判断是否到了该爬的时间
     */
    private boolean shouldCrawlNow(CrawlerConfig.SourceConfig source) {
        int intervalMinutes = source.getCrawlIntervalMinutes();
        if (intervalMinutes <= 0) {
            return false; // 不自动爬取
        }

        Long lastCrawl = infoCacheUtil.getLastCrawlTime(source.getId());
        if (lastCrawl == null) {
            return true; // 从未爬取过
        }

        long intervalMs = intervalMinutes * 60 * 1000L;
        return System.currentTimeMillis() - lastCrawl > intervalMs;
    }
}