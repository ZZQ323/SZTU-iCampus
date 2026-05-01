package cn.edu.sztui.stream.application.external.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实验 3.4 数据采集：Cookie 池调度的命中率 / 切换 / 反爬封禁
 * <p>
 * 4 天观察期，按用户聚合：
 * <ul>
 *   <li>borrow ── 一次"真借"（CookieSourceManager.getAvailableSessionWithUser 返回有效 session）</li>
 *   <li>authFail ── 借出去后被学校 401/403 拒绝（CrawlEngine doCrawlIncremental 检测到）</li>
 *   <li>switch ── 池主动切换到另一个 user（通常因 markInvalidAndSwitch）</li>
 * </ul>
 * <p>
 * 输出两份 CSV：
 * <ul>
 *   <li>{@code infos/runtime-trace/cookie-pool-snapshots.csv} — 每小时整点的累计计数（按 userId）</li>
 *   <li>{@code infos/runtime-trace/cookie-pool-events.csv}    — 即时事件流（switch / authFail）</li>
 * </ul>
 */
@Slf4j
@Component
public class CookiePoolMetrics {

    private static final String SNAPSHOT_HEADER =
            "timestamp,userId,total_borrows,total_auth_fails";
    private static final String EVENT_HEADER =
            "timestamp,event,fromUser,toUser,reason";

    private final Map<String, AtomicLong> borrowCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> authFailCount = new ConcurrentHashMap<>();

    @Value("${experiment.cookie-metrics.snapshot-path:infos/runtime-trace/cookie-pool-snapshots.csv}")
    private String snapshotPath;

    @Value("${experiment.cookie-metrics.event-path:infos/runtime-trace/cookie-pool-events.csv}")
    private String eventPath;

    @Value("${experiment.cookie-metrics.enabled:true}")
    private boolean enabled;

    /** 借出一次有效 session（CookieSourceManager 调用） */
    public void recordBorrow(String userId) {
        if (!enabled || userId == null) return;
        borrowCount.computeIfAbsent(userId, k -> new AtomicLong()).incrementAndGet();
    }

    /** 学校拒绝（401/403 / 解析器检测登录页）→ cookie 已失效 */
    public void recordAuthFail(String userId, String reason) {
        if (!enabled || userId == null) return;
        authFailCount.computeIfAbsent(userId, k -> new AtomicLong()).incrementAndGet();
        appendEvent("AUTH_FAIL", userId, "", reason);
    }

    /** 池主动切换到另一个 user */
    public void recordSwitch(String fromUserId, String toUserId, String reason) {
        if (!enabled) return;
        appendEvent("SWITCH", fromUserId == null ? "" : fromUserId,
                toUserId == null ? "" : toUserId, reason);
    }

    /** 每小时整点 snapshot 当前累计计数到 CSV */
    @Scheduled(cron = "0 0 * * * *")
    public void hourlySnapshot() {
        if (!enabled) return;
        if (borrowCount.isEmpty() && authFailCount.isEmpty()) {
            // 还没有任何借用 → 写一行哨兵，方便看出"曲线起点"
            writeSnapshotRow(Instant.now().toString() + ",__none__,0,0");
            return;
        }
        String ts = Instant.now().toString();
        // 合并所有出现过的 userId
        ConcurrentHashMap<String, Boolean> ids = new ConcurrentHashMap<>();
        borrowCount.keySet().forEach(k -> ids.put(k, true));
        authFailCount.keySet().forEach(k -> ids.put(k, true));
        for (String uid : ids.keySet()) {
            long b = borrowCount.getOrDefault(uid, new AtomicLong()).get();
            long a = authFailCount.getOrDefault(uid, new AtomicLong()).get();
            writeSnapshotRow(ts + "," + uid + "," + b + "," + a);
        }
    }

    private void appendEvent(String event, String fromUser, String toUser, String reason) {
        String row = String.join(",",
                Instant.now().toString(), event, fromUser, toUser, sanitize(reason));
        try {
            writeRow(eventPath, EVENT_HEADER, row);
        } catch (IOException e) {
            log.warn("[CookiePoolMetrics] event write failed: {}", e.getMessage());
        }
    }

    private void writeSnapshotRow(String row) {
        try {
            writeRow(snapshotPath, SNAPSHOT_HEADER, row);
        } catch (IOException e) {
            log.warn("[CookiePoolMetrics] snapshot write failed: {}", e.getMessage());
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace(',', '_').replace('\n', ' ').replace('\r', ' ');
    }

    private void writeRow(String path, String header, String row) throws IOException {
        Path p = Paths.get(path);
        Path parent = p.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        boolean newFile = !Files.exists(p);
        try (var w = Files.newBufferedWriter(p,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (newFile) {
                w.write(header);
                w.newLine();
            }
            w.write(row);
            w.newLine();
        }
    }
}
