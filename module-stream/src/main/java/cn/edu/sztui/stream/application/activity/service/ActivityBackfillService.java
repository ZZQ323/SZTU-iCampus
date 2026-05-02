package cn.edu.sztui.stream.application.activity.service;

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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 活动全量补扫
 * <p>
 * 用途：把 Redis 里所有现存公文（{@code ai.activity.default-channels} 配置的频道）走一遍 LLM 抽取流程。
 * 适用场景：
 * <ul>
 *   <li>首次启用活动模块（之前没开 auto-process），把历史数据一次性入索引</li>
 *   <li>升级 prompt 版本（cache-version bump）后让旧文章用新 prompt 重判</li>
 *   <li>清空 Redis 后冷启动，需要回填活动索引</li>
 * </ul>
 * <p>
 * 内部委托 {@link ActivityScanService#autoProcess}。后者已有规则预筛 +
 * LLM 30 天缓存，重复扫描已处理过的文章只会命中缓存，<b>不烧 token</b>。
 * 实际成本 ≈ 通过预筛的新文章数 × 单次 LLM 调用价（qwen-turbo 几分钱/百篇）。
 * <p>
 * 两种触发方式：
 * <ul>
 *   <li><b>启动自动</b>：{@code ai.activity.scan-on-startup=true}（默认 false）→
 *       应用启动 30s 后异步全量扫一次</li>
 *   <li><b>手动 admin 接口</b>：{@code POST /admin/activity/backfill} 任意时刻触发</li>
 * </ul>
 */
@Slf4j
@Service
public class ActivityBackfillService {

    /** 一次从 timeline 取多少条；与 LLM 速率耦合，不要太大 */
    private static final int PAGE_SIZE = 100;

    @Resource
    private ActivityScanService scanService;

    @Resource
    private InfoCacheUtil infoCacheUtil;

    @Value("${ai.activity.default-channels:announcement,job}")
    private List<String> defaultChannels;

    /** 简单的串行守卫：避免启动自动 + admin 手动同时跑 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 进度状态（对外暴露便于 admin 端点查询）*/
    private final ProgressState progress = new ProgressState();

    /**
     * 同步执行（阻塞）。caller 决定是否在 @Async 里调用。
     * <p>
     * 不支持 per-item force：要重判已扫过的文章，请 bump
     * {@code ai.activity.cache-version}（如 v3 → v4），所有旧 cache key 自然失效。
     * 这样比传 force 参数更干净——保留旧 prompt 的判断结果便于事后对照。
     *
     * @param channels 要扫的频道；null/empty 用 default-channels
     * @return 进度快照（已完成时 stage=DONE）
     */
    public ProgressSnapshot backfill(List<String> channels) {
        if (!running.compareAndSet(false, true)) {
            log.warn("[activity-backfill] 已有任务在运行，跳过");
            return progress.snapshot("ALREADY_RUNNING");
        }
        try {
            List<String> targets = (channels == null || channels.isEmpty()) ? defaultChannels : channels;
            progress.reset(targets);
            log.info("[activity-backfill] 开始全量补扫: channels={}", targets);

            for (String channelId : targets) {
                progress.startChannel(channelId);
                backfillChannel(channelId);
            }
            log.info("[activity-backfill] 完成: total={} processed={} errors={} elapsed={}ms",
                    progress.totalSeen.get(), progress.processed.get(),
                    progress.errors.get(),
                    System.currentTimeMillis() - progress.startTime);
            return progress.snapshot("DONE");
        } finally {
            running.set(false);
        }
    }

    private void backfillChannel(String channelId) {
        Long total = infoCacheUtil.getTotalCount(channelId);
        long n = total == null ? 0 : total;
        log.info("[activity-backfill] channel={} 待扫描 {} 条", channelId, n);

        int page = 1;
        while (true) {
            List<InfoItemMeta> items = infoCacheUtil.getList(channelId, page, PAGE_SIZE);
            if (items == null || items.isEmpty()) break;

            for (InfoItemMeta meta : items) {
                progress.totalSeen.incrementAndGet();
                try {
                    scanService.autoProcess(meta);
                    progress.processed.incrementAndGet();
                } catch (Exception e) {
                    progress.errors.incrementAndGet();
                    log.warn("[activity-backfill] failed: channel={} id={} err={}",
                            channelId, meta.getId(), e.getMessage());
                }
            }

            log.info("[activity-backfill] channel={} progress page={} total={} processed={}",
                    channelId, page, progress.totalSeen.get(), progress.processed.get());

            if (items.size() < PAGE_SIZE) break;  // 最后一页
            page++;

            // 喘口气，避免对 LLM/Redis 形成持续压力
            try { Thread.sleep(200); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); break;
            }
        }
    }

    /** 启动自动触发：opt-in。默认关。 */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    @ConditionalOnProperty(value = "ai.activity.scan-on-startup", havingValue = "true")
    public void onStartup() {
        try {
            // 等爬虫和其他 startup 任务先稳定下来
            Thread.sleep(30_000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }
        backfill(null);
    }

    /** 当前进度查询（admin 用） */
    public ProgressSnapshot currentProgress() {
        return progress.snapshot(running.get() ? "RUNNING" : "IDLE");
    }

    // ==================== 进度状态 ====================

    private static class ProgressState {
        final AtomicLong totalSeen = new AtomicLong();
        final AtomicLong processed = new AtomicLong();
        final AtomicLong skippedNoMatch = new AtomicLong();
        final AtomicLong llmInvoked = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        volatile String currentChannel = "";
        volatile List<String> channels = List.of();
        volatile long startTime = 0;

        synchronized void reset(List<String> chs) {
            totalSeen.set(0);
            processed.set(0);
            skippedNoMatch.set(0);
            llmInvoked.set(0);
            errors.set(0);
            channels = chs;
            currentChannel = "";
            startTime = System.currentTimeMillis();
        }

        void startChannel(String ch) { currentChannel = ch; }

        ProgressSnapshot snapshot(String stage) {
            ProgressSnapshot s = new ProgressSnapshot();
            s.stage = stage;
            s.channels = channels;
            s.currentChannel = currentChannel;
            s.totalSeen = totalSeen.get();
            s.processed = processed.get();
            s.errors = errors.get();
            s.elapsedMs = startTime == 0 ? 0 : System.currentTimeMillis() - startTime;
            return s;
        }
    }

    /** 对外暴露的进度数据（lombok 不引入额外依赖，手写 getter 简化）*/
    public static class ProgressSnapshot {
        public String stage;
        public List<String> channels;
        public String currentChannel;
        public long totalSeen;
        public long processed;
        public long errors;
        public long elapsedMs;
    }
}
