package cn.edu.sztui.stream.application.external.engine;

import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfigLoader;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据源初始化任务
 * <p>
 * 两种触发方式：
 * 1. 应用启动后：立即初始化所有不需要登录的公开源（学校官网、学院等）
 * 2. 用户登录后：初始化需要登录的源（公文通等）
 * <p>
 * ⭐ 每次启动都强制重新初始化：
 * - 公开源：立即执行
 * - 需登录源（公文通）：清除 initialized 标记，等用户登录后由事件触发执行
 */
@Slf4j
@Component
public class SourceInitTask {

    @Resource
    private CrawlEngine crawlEngine;

    @Resource
    private CrawlerConfigLoader configLoader;

    @Resource
    private InfoCacheUtil infoCacheUtil;

    private final AtomicBoolean publicInitializing = new AtomicBoolean(false);
    private final AtomicBoolean authInitializing = new AtomicBoolean(false);

    /**
     * 应用启动后，立即初始化所有公开源 + 清除需登录源的 initialized 标记
     * <p>
     * ⭐ 每次启动都强制重新初始化所有源：
     * - 公开源：清除标记后立即爬取
     * - 需登录源（公文通等）：只清除标记，等用户登录后由 triggerAuthSourceInit 执行
     *   AnnouncementFastScheduler 也会在检测到 cookie 后自动触发增量爬取
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void initPublicSourcesOnStartup() {
        if (!publicInitializing.compareAndSet(false, true)) {
            return;
        }

        try {
            List<CrawlerConfig.SourceConfig> sources = configLoader.getEnabledSources();
            List<CrawlerConfig.SourceConfig> publicSources = new ArrayList<>();
            List<CrawlerConfig.SourceConfig> authSources = new ArrayList<>();

            for (CrawlerConfig.SourceConfig source : sources) {
                if (source.isRequiresAuth()) {
                    authSources.add(source);
                } else {
                    publicSources.add(source);
                }
            }

            // ⭐ 强制清除所有源的 initialized 标记（公开源 + 需登录源）
            for (CrawlerConfig.SourceConfig source : publicSources) {
                infoCacheUtil.clearSourceInitialized(source.getId());
            }
            for (CrawlerConfig.SourceConfig source : authSources) {
                infoCacheUtil.clearSourceInitialized(source.getId());
            }
            log.info("已清除所有数据源 initialized 标记: 公开 {} 个, 需登录 {} 个",
                    publicSources.size(), authSources.size());

            // ⭐ 清除全局 feed timeline（旧缓存可能包含已更名/拆分的频道数据）
            infoCacheUtil.clearFeedTimeline();

            if (publicSources.isEmpty()) {
                log.info("无公开数据源需要初始化");
                return;
            }

            // 立即初始化公开源
            log.info("开始初始化 {} 个公开数据源...", publicSources.size());
            int initCount = 0;
            int failCount = 0;

            for (CrawlerConfig.SourceConfig source : publicSources) {
                try {
                    crawlEngine.initSource(source.getId(), null);
                    initCount++;
                } catch (Exception e) {
                    log.error("公开源初始化失败: {}, error={}", source.getId(), e.getMessage());
                    failCount++;
                }
            }

            log.info("公开数据源初始化完成: 成功 {} 个, 失败 {} 个", initCount, failCount);

        } finally {
            publicInitializing.set(false);
        }
    }

    /**
     * 用户登录后，初始化需要登录的源（requiresAuth=true）
     * <p>
     * 由 UserLoginEventListener 调用。
     * 这些源需要用户的 Cookie 才能访问（如公文通）。
     */
    public void triggerAuthSourceInit(String userId) {
        if (!authInitializing.compareAndSet(false, true)) {
            log.info("需登录数据源初始化进行中，跳过");
            return;
        }

        try {
            List<CrawlerConfig.SourceConfig> sources = configLoader.getEnabledSources();
            int initCount = 0;
            int skipCount = 0;

            for (CrawlerConfig.SourceConfig source : sources) {
                if (!source.isRequiresAuth()) {
                    continue;
                }

                if (infoCacheUtil.isSourceInitialized(source.getId())) {
                    skipCount++;
                    continue;
                }

                try {
                    crawlEngine.initSource(source.getId(), userId);
                    initCount++;
                } catch (Exception e) {
                    log.error("初始化源失败: {}, error={}", source.getId(), e.getMessage());
                }
            }

            log.info("需登录数据源初始化完成: 初始化 {} 个, 已跳过 {} 个", initCount, skipCount);

        } finally {
            authInitializing.set(false);
        }
    }

    /**
     * 兼容旧接口（UserLoginEventListener 调用）
     */
    public void triggerInit(String userId) {
        triggerAuthSourceInit(userId);
    }
}
