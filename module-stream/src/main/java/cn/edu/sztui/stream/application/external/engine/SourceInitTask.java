package cn.edu.sztui.stream.application.external.engine;

import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfigLoader;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据源初始化任务
 * <p>
 * 替代原 AnnouncementInitTask。
 * 由 UserLoginEventListener 在首次登录时触发。
 * 遍历所有 enabled 的源，未初始化的执行全量爬取。
 * <p>
 * 文件位置：module-stream/.../application/external/engine/SourceInitTask.java
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

    /**
     * 防止重复初始化
     */
    private final AtomicBoolean initializing = new AtomicBoolean(false);

    /**
     * 触发所有未初始化源的全量爬取
     *
     * @param userId 登录用户的 userId（用于获取 Cookie）
     */
    public void triggerInit(String userId) {
        if (!initializing.compareAndSet(false, true)) {
            log.info("数据源初始化进行中，跳过重复触发");
            return;
        }

        try {
            List<CrawlerConfig.SourceConfig> sources = configLoader.getEnabledSources();
            int initCount = 0;
            int skipCount = 0;

            for (CrawlerConfig.SourceConfig source : sources) {
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

            log.info("数据源初始化完成: 初始化 {} 个, 已跳过 {} 个", initCount, skipCount);

        } finally {
            initializing.set(false);
        }
    }
}