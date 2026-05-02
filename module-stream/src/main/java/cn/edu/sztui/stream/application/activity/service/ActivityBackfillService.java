package cn.edu.sztui.stream.application.activity.service;

import cn.edu.sztui.common.cache.util.CacheUtil;
import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.bean.TokenMessage;
import cn.edu.sztui.common.util.smarthttp.SmartCookieConverter;
import cn.edu.sztui.stream.application.external.engine.CookieSourceManager;
import cn.edu.sztui.stream.application.service.InfoService;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ContentParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult.InfoItemMeta;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 活动全量补扫（自适应 + 重试队列版）
 * <p>
 * 核心设计：
 * <ul>
 *   <li><b>每条文章先确认能拿到正文</b>：detail 缓存命中 OR cookie 池可用能拉详情</li>
 *   <li><b>拉不到正文 → 不调 LLM</b>，避免污染 30 天缓存。文章入
 *       Redis Set {@code activity:retry-queue}，等待 {@link ActivityRetryTask}
 *       定时重试</li>
 *   <li><b>不依赖 UserContext</b>（HTTP 线程才有），走
 *       {@link CookieSourceManager#getAvailableSessionWithUser()} 的 cookie 池路径</li>
 * </ul>
 * <p>
 * "彻底重来" 流程（用户操作）：
 * <ol>
 *   <li>清 LLM cache：{@code redis-cli --scan --pattern 'dev:sztu:cache:activity:extract:*' | xargs redis-cli del}</li>
 *   <li>清活动索引：{@code redis-cli del dev:sztu:cache:activity:timeline dev:sztu:cache:activity:pending}</li>
 *   <li>登录小程序，确保 WS 在线（cookie 池有数据）</li>
 *   <li>POST {@code /admin/activity/backfill}</li>
 *   <li>有 cookie 的部分立即被处理；其他文章入 retry queue，每 15 min 自动重试</li>
 * </ol>
 */
@Slf4j
@Service
public class ActivityBackfillService {

    /** 一次从 timeline 取多少条 */
    private static final int PAGE_SIZE = 100;

    /** 重试队列 raw key（cacheUtil 自动加 dev:sztu:cache: 前缀）*/
    static final String RETRY_QUEUE_KEY = "activity:retry-queue";

    @Resource
    private ActivityScanService scanService;

    @Resource
    private InfoCacheUtil infoCacheUtil;

    @Resource
    private InfoService infoService;

    @Resource
    private CookieSourceManager cookieSourceManager;

    @Resource
    private CacheUtil cacheUtil;

    @Value("${ai.activity.default-channels:announcement,job}")
    private List<String> defaultChannels;

    @Value("${ai.activity.backfill-fetch-detail:true}")
    private boolean fetchDetailOnMiss;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ProgressState progress = new ProgressState();

    // ==================== 公开 API ====================

    public ProgressSnapshot backfill(List<String> channels) {
        if (!running.compareAndSet(false, true)) {
            log.warn("[activity-backfill] 已有任务在运行，跳过");
            return progress.snapshot("ALREADY_RUNNING");
        }
        try {
            List<String> targets = (channels == null || channels.isEmpty()) ? defaultChannels : channels;
            progress.reset(targets);
            boolean cookieAvailable = cookieSourceManager.hasAvailableCookie();
            log.info("[activity-backfill] 开始: channels={} cookieAvailable={}（{} 时无法拉详情，详情缺失文章会入 retry-queue 等待重试）",
                    targets, cookieAvailable, cookieAvailable ? "ok" : "no");

            for (String channelId : targets) {
                progress.startChannel(channelId);
                backfillChannel(channelId);
            }
            log.info("[activity-backfill] 完成: total={} processed={} queuedRetry={} errors={} elapsed={}ms",
                    progress.totalSeen.get(), progress.processed.get(),
                    progress.queuedRetry.get(), progress.errors.get(),
                    System.currentTimeMillis() - progress.startTime);
            return progress.snapshot("DONE");
        } finally {
            running.set(false);
        }
    }

    public ProgressSnapshot currentProgress() {
        return progress.snapshot(running.get() ? "RUNNING" : "IDLE");
    }

    // ==================== 给 RetryTask 用 ====================

    /**
     * 处理单条 meta 的完整流程：先 ensureDetail，OK 才调 LLM 抽取，否则入重试队列。
     * 共享给 backfill 主流程和定时重试。
     *
     * @return true=成功处理；false=入了重试队列（detail 拿不到）
     */
    boolean processWithRetryFallback(InfoItemMeta meta) {
        try {
            ensureDetailCached(meta);
            scanService.autoProcess(meta);
            cacheUtil.sRem(RETRY_QUEUE_KEY, queueMember(meta));
            return true;
        } catch (DetailUnavailableException e) {
            cacheUtil.sAdd(RETRY_QUEUE_KEY, queueMember(meta));
            log.debug("[activity-backfill] queued for retry: {} ({})",
                    queueMember(meta), e.getMessage());
            return false;
        }
    }

    Set<Object> retryQueueMembers() {
        return cacheUtil.sMembers(RETRY_QUEUE_KEY);
    }

    public long retryQueueSize() {
        Long n = cacheUtil.sCard(RETRY_QUEUE_KEY);
        return n == null ? 0 : n;
    }

    InfoItemMeta resolveMetaFromQueueMember(String member) {
        if (member == null) return null;
        int colon = member.indexOf(':');
        if (colon <= 0 || colon == member.length() - 1) return null;
        String channelId = member.substring(0, colon);
        String articleId = member.substring(colon + 1);
        return infoCacheUtil.getMeta(channelId, articleId);
    }

    void removeFromRetryQueue(String member) {
        cacheUtil.sRem(RETRY_QUEUE_KEY, member);
    }

    // ==================== 内部：分页扫频道 ====================

    private void backfillChannel(String channelId) {
        Long total = infoCacheUtil.getTotalCount(channelId);
        log.info("[activity-backfill] channel={} 待扫描 {} 条", channelId, total == null ? 0 : total);

        int page = 1;
        while (true) {
            List<InfoItemMeta> items = infoCacheUtil.getList(channelId, page, PAGE_SIZE);
            if (items == null || items.isEmpty()) break;

            for (InfoItemMeta meta : items) {
                progress.totalSeen.incrementAndGet();
                try {
                    boolean ok = processWithRetryFallback(meta);
                    if (ok) progress.processed.incrementAndGet();
                    else progress.queuedRetry.incrementAndGet();
                } catch (Exception e) {
                    progress.errors.incrementAndGet();
                    log.warn("[activity-backfill] failed: channel={} id={} err={}",
                            channelId, meta.getId(), e.getMessage());
                }
            }

            log.info("[activity-backfill] channel={} page={} total={} processed={} queued={}",
                    channelId, page, progress.totalSeen.get(),
                    progress.processed.get(), progress.queuedRetry.get());

            if (items.size() < PAGE_SIZE) break;
            page++;
            try { Thread.sleep(200); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); break;
            }
        }
    }

    // ==================== 详情拉取（自适应）====================

    /**
     * 确保正文已落 24h 缓存。
     * <ul>
     *   <li>cache 命中 → 直接返回</li>
     *   <li>cache miss + cookie 池可用 → 走 cookie 池拉详情，写入 cache</li>
     *   <li>cache miss + cookie 池空 → 抛 {@link DetailUnavailableException}，
     *       backfill 上层把这条文章入 retry queue，<b>不</b>调 LLM</li>
     * </ul>
     */
    private void ensureDetailCached(InfoItemMeta meta) throws DetailUnavailableException {
        if (!fetchDetailOnMiss) return;
        if (meta.getChannelId() == null || meta.getId() == null) return;

        ContentParserResult cached = infoCacheUtil.getContent(meta.getChannelId(), meta.getId());
        if (cached != null && StringUtils.hasText(cached.getContent())) {
            return;  // 已有
        }

        if (!cookieSourceManager.hasAvailableCookie()) {
            throw new DetailUnavailableException("no cookie pool available");
        }

        // 借 cookie 池一份临时 UserContext，让 InfoService.crawlDetail 通过鉴权
        CookieSourceManager.CookieSessionPair pair;
        try {
            pair = cookieSourceManager.getAvailableSessionWithUser();
        } catch (CookieSourceManager.NoCookieAvailableException e) {
            throw new DetailUnavailableException("cookie pool race: " + e.getMessage());
        }

        String cookiesJson = SmartCookieConverter.smartCookiesToJson(pair.getOriginalCookies());
        TokenMessage ctx = new TokenMessage();
        ctx.setSchoolCookiesJson(cookiesJson);
        ctx.setUserId(pair.getUserId());
        UserContext.setContext(ctx);
        try {
            ContentParserResult result = infoService.getDetail(meta.getChannelId(), meta.getId(), null);
            if (result == null || !result.isSuccess() || !StringUtils.hasText(result.getContent())) {
                throw new DetailUnavailableException("detail fetch returned empty/failed");
            }
        } catch (DetailUnavailableException re) {
            throw re;
        } catch (Exception e) {
            throw new DetailUnavailableException("detail fetch error: " + e.getMessage());
        } finally {
            UserContext.clear();
        }
    }

    private static String queueMember(InfoItemMeta meta) {
        return meta.getChannelId() + ":" + meta.getId();
    }

    // ==================== 启动自动触发 ====================

    @Async
    @EventListener(ApplicationReadyEvent.class)
    @ConditionalOnProperty(value = "ai.activity.scan-on-startup", havingValue = "true")
    public void onStartup() {
        try { Thread.sleep(30_000); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt(); return;
        }
        backfill(null);
    }

    // ==================== 内部类型 ====================

    static class DetailUnavailableException extends RuntimeException {
        DetailUnavailableException(String message) { super(message); }
    }

    private static class ProgressState {
        final AtomicLong totalSeen = new AtomicLong();
        final AtomicLong processed = new AtomicLong();
        final AtomicLong queuedRetry = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        volatile String currentChannel = "";
        volatile List<String> channels = List.of();
        volatile long startTime = 0;

        synchronized void reset(List<String> chs) {
            totalSeen.set(0); processed.set(0); queuedRetry.set(0); errors.set(0);
            channels = chs; currentChannel = ""; startTime = System.currentTimeMillis();
        }
        void startChannel(String ch) { currentChannel = ch; }

        ProgressSnapshot snapshot(String stage) {
            ProgressSnapshot s = new ProgressSnapshot();
            s.stage = stage; s.channels = channels; s.currentChannel = currentChannel;
            s.totalSeen = totalSeen.get(); s.processed = processed.get();
            s.queuedRetry = queuedRetry.get(); s.errors = errors.get();
            s.elapsedMs = startTime == 0 ? 0 : System.currentTimeMillis() - startTime;
            return s;
        }
    }

    public static class ProgressSnapshot {
        public String stage;
        public List<String> channels;
        public String currentChannel;
        public long totalSeen;
        public long processed;
        public long queuedRetry;
        public long errors;
        public long elapsedMs;
    }
}
