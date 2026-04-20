package cn.edu.sztui.stream.application.external.engine;

import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfigLoader;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * 1. 应用启动后：初始化所有不需要登录的公开源（学校官网、学院等）
 * 2. 用户登录后：初始化需要登录的源（公文通等）
 * <p>
 * ⭐ 增量 vs 强制重爬：
 * - 默认：只初始化 {@code initialized=false} 的源（重启不重爬，省带宽 + 对学校友好）
 * - 开关 {@code crawler.force-reinit=true}（yml 或命令行 --crawler.force-reinit=true）：
 *   清除所有源的 initialized 标记 + 清空 feed timeline，重新全量爬取。
 *   仅在爬虫 parser 升级 / 数据 schema 变更 / 想强制刷新时使用。
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

    /** 是否强制清除 initialized 标记 + 清空 feed 重爬。默认 false（只补爬未初始化的源） */
    @Value("${crawler.force-reinit:false}")
    private boolean forceReinit;

    private final AtomicBoolean publicInitializing = new AtomicBoolean(false);
    private final AtomicBoolean authInitializing = new AtomicBoolean(false);

    /**
     * 应用启动后初始化公开源。
     * <p>
     * 默认增量：只爬 {@code initialized=false} 的源，重启不会重复扫学校。<br>
     * {@code crawler.force-reinit=true} 时：清 initialized + 清 feed timeline + 全量重爬。
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

            if (forceReinit) {
                for (CrawlerConfig.SourceConfig source : publicSources) {
                    infoCacheUtil.clearSourceInitialized(source.getId());
                }
                for (CrawlerConfig.SourceConfig source : authSources) {
                    infoCacheUtil.clearSourceInitialized(source.getId());
                }
                infoCacheUtil.clearFeedTimeline();
                log.warn("[force-reinit] 已清除所有 initialized 标记 + 全局 feed timeline: 公开 {} 个, 需登录 {} 个",
                        publicSources.size(), authSources.size());
            } else {
                log.info("启动初始化（增量模式）: 已初始化的源跳过，如需全量重爬请加 --crawler.force-reinit=true");
            }

            if (publicSources.isEmpty()) {
                log.info("无公开数据源需要初始化");
                return;
            }

            int initCount = 0;
            int skipCount = 0;
            int failCount = 0;

            for (CrawlerConfig.SourceConfig source : publicSources) {
                if (!forceReinit && infoCacheUtil.isSourceInitialized(source.getId())) {
                    skipCount++;
                    continue;
                }
                try {
                    crawlEngine.initSource(source.getId(), null);
                    initCount++;
                } catch (Exception e) {
                    log.error("公开源初始化失败: {}, error={}", source.getId(), e.getMessage());
                    failCount++;
                }
            }

            log.info("公开数据源初始化完成: 初始化 {} 个, 已跳过 {} 个, 失败 {} 个",
                    initCount, skipCount, failCount);

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
