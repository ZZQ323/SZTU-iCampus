package cn.edu.sztui.stream.application.activity.service;

import cn.edu.sztui.stream.application.external.engine.CookieSourceManager;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult.InfoItemMeta;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 定时扫"活动重试队列"，把因为 cookie 不可用而暂存的文章再过一遍流程。
 * <p>
 * 策略：
 * <ul>
 *   <li>每 15 分钟跑一次（cron 配置）</li>
 *   <li>cookie 池空 → 跳过本轮（重试也拉不到详情）</li>
 *   <li>每条委托 {@link ActivityBackfillService#processWithRetryFallback} —— 成功就出队，仍失败留着</li>
 *   <li>元数据已不存在（被清掉/key 错位）→ 直接出队</li>
 * </ul>
 * <p>
 * 与 backfill 的协作：backfill 把"暂时无法处理"的文章塞进队列，retry task 兜底慢慢消化。
 * 用户登录上线 → 队列里的会在最多 15 min 内被处理掉。
 */
@Slf4j
@Component
public class ActivityRetryTask {

    @Resource
    private ActivityBackfillService backfillService;

    @Resource
    private CookieSourceManager cookieSourceManager;

    /** 每 15 分钟一次：分钟数 0/15/30/45 */
    @Scheduled(cron = "0 */15 * * * *")
    public void retry() {
        runOnce();
    }

    /** 手动触发用（admin 端点）。同样的策略，立即跑一轮。 */
    public RetryReport runOnce() {
        long size = backfillService.retryQueueSize();
        if (size == 0) {
            return new RetryReport(0, 0, 0, 0, "EMPTY");
        }
        if (!cookieSourceManager.hasAvailableCookie()) {
            log.info("[activity-retry] {} 条待重试，但 cookie 池空，本轮跳过", size);
            return new RetryReport(size, 0, 0, 0, "NO_COOKIE");
        }

        Set<Object> queued = backfillService.retryQueueMembers();
        if (queued == null || queued.isEmpty()) {
            return new RetryReport(0, 0, 0, 0, "EMPTY");
        }

        log.info("[activity-retry] processing {} queued items", queued.size());
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger stillFailed = new AtomicInteger();
        AtomicInteger metaMissing = new AtomicInteger();

        for (Object o : queued) {
            String member = o.toString();
            InfoItemMeta meta = backfillService.resolveMetaFromQueueMember(member);
            if (meta == null) {
                backfillService.removeFromRetryQueue(member);
                metaMissing.incrementAndGet();
                continue;
            }
            try {
                boolean ok = backfillService.processWithRetryFallback(meta);
                if (ok) succeeded.incrementAndGet();
                else stillFailed.incrementAndGet();
            } catch (Exception e) {
                stillFailed.incrementAndGet();
                log.warn("[activity-retry] item failed: {} err={}", member, e.getMessage());
            }
        }
        log.info("[activity-retry] done: succeeded={} stillFailed={} metaMissing={}",
                succeeded.get(), stillFailed.get(), metaMissing.get());
        return new RetryReport(queued.size(), succeeded.get(), stillFailed.get(), metaMissing.get(), "OK");
    }

    public static class RetryReport {
        public long queueSize;
        public int succeeded;
        public int stillFailed;
        public int metaMissing;
        public String status;
        public RetryReport(long queueSize, int succeeded, int stillFailed, int metaMissing, String status) {
            this.queueSize = queueSize;
            this.succeeded = succeeded;
            this.stillFailed = stillFailed;
            this.metaMissing = metaMissing;
            this.status = status;
        }
    }
}
