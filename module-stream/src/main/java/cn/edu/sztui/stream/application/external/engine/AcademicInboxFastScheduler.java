package cn.edu.sztui.stream.application.external.engine;

import cn.edu.sztui.base.application.service.AcademicService;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfigLoader;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 教务内网（acdm-inbox）轮询调度器
 * <p>
 * 为什么独立于 {@link AnnouncementFastScheduler}：
 * <ul>
 *   <li>用户集不同：公文通用网关根域 cookies 即可；acdm-* 需要 jwxt 子域专属 cookies（只能从调用过 /acdm/v1/init 的用户拿）</li>
 *   <li>失败自愈路径不同：jsxsd session 可以通过重新跑一次 {@link AcademicService#initInternal} 续命，公文通不存在这种概念</li>
 * </ul>
 * <p>
 * 周期 60 秒（教务消息变动不频繁）。tick 内：
 * <ol>
 *   <li>取在线且有 jwxt cookies 的 userId 列表 —— 没人就直接跳过</li>
 *   <li>对每个 userId 遍历 acdm-* 源，以该用户 cookies 爬取</li>
 *   <li>{@link CrawlResult#isAuthError()} → 调 {@code initInternal} 重试一次；还不行就本轮放弃</li>
 * </ol>
 * <p>
 * 不发任何事件 —— 自愈路径不触发 {@code AcademicSessionReadyEvent}，避免 listener 回环。
 */
@Slf4j
@Component
public class AcademicInboxFastScheduler {

    @Resource
    private CrawlEngine crawlEngine;

    @Resource
    private CrawlerConfigLoader configLoader;

    @Resource
    private InfoCacheUtil infoCacheUtil;

    @Resource
    private CookieSourceManager cookieSourceManager;

    @Resource
    private AcademicService academicService;

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    @PostConstruct
    public void init() {
        log.info("AcademicInboxFastScheduler 已初始化（60s tick）");
    }

    @Scheduled(fixedRate = 60000, initialDelay = 45000)
    public void tickAcademic() {
        List<String> users = cookieSourceManager.getOnlineUsersWithAcdmCookies();
        if (users.isEmpty()) {
            log.debug("教务内网轮询: 无在线且有 jwxt cookies 的用户，跳过");
            return;
        }

        List<CrawlerConfig.SourceConfig> sources = configLoader.getEnabledSources().stream()
                .filter(s -> "acdm-inbox".equals(s.getParserType()))
                .toList();

        if (sources.isEmpty()) return;

        for (String userId : users) {
            for (CrawlerConfig.SourceConfig source : sources) {
                if (!shouldCrawlNow(source)) continue;

                try {
                    crawlOneWithSelfHeal(source, userId);
                } catch (Exception e) {
                    log.error("教务内网爬取异常: userId={}, source={}, error={}",
                            userId, source.getId(), e.getMessage());
                }
            }
        }
    }

    /**
     * 单次爬取 + 自愈重试一次。
     */
    private void crawlOneWithSelfHeal(CrawlerConfig.SourceConfig source, String userId) {
        CrawlResult result = crawlEngine.crawlIncremental(source.getId(), userId);

        if (!result.isAuthError()) {
            if (result.getNewCount() > 0) {
                log.info("教务内网爬取: {} → {} 条新内容", source.getId(), result.getNewCount());
            } else if (result.isSuccess()) {
                log.debug("教务内网爬取无新内容: source={}, userId={}", source.getId(), userId);
            } else {
                log.warn("教务内网爬取失败: source={}, userId={}, error={}",
                        source.getId(), userId, result.getErrorMessage());
            }
            return;
        }

        // authExpired：调用者已经在 Redis 里存过 jwxt cookies，但 session 过期了 → reactive re-init 一次
        log.info("教务内网 session 过期，尝试自愈: userId={}, source={}", userId, source.getId());

        ProxySession proxy = authSessionCacheUtil.getSession(userId);
        if (proxy == null || !StringUtils.hasText(proxy.getCookiesJson())) {
            log.warn("自愈失败: Redis 里找不到 userId={} 的 cookies", userId);
            return;
        }

        String refreshed = academicService.initInternal(userId, proxy.getCookiesJson());
        if (refreshed == null) {
            log.warn("自愈失败: initInternal 返回 null, userId={}", userId);
            return;
        }

        CrawlResult retry = crawlEngine.crawlIncremental(source.getId(), userId);
        if (retry.isAuthError()) {
            log.warn("自愈后仍为登录页，本轮放弃: userId={}, source={}", userId, source.getId());
        } else if (retry.getNewCount() > 0) {
            log.info("教务内网自愈成功: {} → {} 条新内容", source.getId(), retry.getNewCount());
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
