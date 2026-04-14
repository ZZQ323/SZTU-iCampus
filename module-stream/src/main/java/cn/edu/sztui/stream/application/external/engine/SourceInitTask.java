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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据源初始化任务
 * <p>
 * 两种触发方式：
 * 1. 应用启动后：立即初始化所有不需要登录的公开源（学校官网、学院等）
 * 2. 用户登录后：初始化需要登录的源（公文通等）
 * <p>
 * 公开源每次启动都强制重新初始化（清除 initialized 标记），
 * 确保开发调试时 Redis 清空后也能正常加载。
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
     * 应用启动后，立即初始化所有公开源（requiresAuth=false）
     * <p>
     * 每次启动都强制重新初始化：先清除 initialized 标记，再执行初始化。
     * 公开源不需要 Cookie，不需要等用户登录。
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void initPublicSourcesOnStartup() {
        if (!publicInitializing.compareAndSet(false, true)) {
            return;
        }

        try {
            List<CrawlerConfig.SourceConfig> sources = configLoader.getEnabledSources();
            List<CrawlerConfig.SourceConfig> publicSources = sources.stream()
                    .filter(s -> !s.isRequiresAuth())
                    .toList();

            if (publicSources.isEmpty()) {
                log.info("无公开数据源需要初始化");
                return;
            }

            // 强制清除 initialized 标记（每次启动都重新初始化公开源）
            for (CrawlerConfig.SourceConfig source : publicSources) {
                infoCacheUtil.clearSourceInitialized(source.getId());
            }

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
                    continue; // 公开源已在启动时初始化
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
