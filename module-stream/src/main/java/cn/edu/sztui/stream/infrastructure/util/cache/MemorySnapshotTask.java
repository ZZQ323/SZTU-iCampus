package cn.edu.sztui.stream.infrastructure.util.cache;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Properties;

/**
 * 实验 3.2 数据采集：Redis 内存随时间增长曲线
 * <p>
 * 每小时采一次 {@code INFO memory} + {@code DBSIZE}，append 到本地 CSV。
 * 5/1 启动 → 5/5 交稿 ≈ 96 小时数据点，足够画"启动期增长 + 稳态震荡"曲线。
 * <p>
 * 文件位置：{@code infos/runtime-trace/redis-memory.csv}
 * <p>
 * 注意：本类直接注入 {@link RedisConnectionFactory} 调用 INFO/DBSIZE 命令，
 * 这是 admin 级 metric 采集，不是业务数据读写，所以**不走 CacheUtil**。
 * CacheUtil 的归一化前缀机制对 INFO 这种 server-side 命令也无意义。
 */
@Slf4j
@Component
public class MemorySnapshotTask {

    private static final String CSV_HEADER =
            "timestamp,used_memory_bytes,used_memory_peak_bytes,used_memory_rss_bytes,"
                    + "mem_fragmentation_ratio,db_size,used_memory_human,used_memory_peak_human";

    private final RedisConnectionFactory redisConnectionFactory;

    @Value("${experiment.memory-snapshot.path:infos/runtime-trace/redis-memory.csv}")
    private String csvPath;

    @Value("${experiment.memory-snapshot.enabled:true}")
    private boolean enabled;

    public MemorySnapshotTask(RedisConnectionFactory redisConnectionFactory) {
        this.redisConnectionFactory = redisConnectionFactory;
    }

    @PostConstruct
    public void onStart() {
        if (!enabled) {
            log.info("[MemorySnapshot] disabled");
            return;
        }
        // 启动后立刻采一次（不等第一个整点），论文里"day 0 数据点"
        try {
            Thread.sleep(15_000);  // 等 Redis 客户端就绪
            snapshot();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** 每小时整点采集 */
    @Scheduled(cron = "0 0 * * * *")
    public void hourly() {
        if (enabled) snapshot();
    }

    private void snapshot() {
        try {
            try (RedisConnection conn = redisConnectionFactory.getConnection()) {
                Properties info = conn.serverCommands().info("memory");
                Long dbSize = conn.serverCommands().dbSize();

                String row = String.join(",",
                        Instant.now().toString(),
                        prop(info, "used_memory"),
                        prop(info, "used_memory_peak"),
                        prop(info, "used_memory_rss"),
                        prop(info, "mem_fragmentation_ratio"),
                        dbSize == null ? "" : dbSize.toString(),
                        sanitize(prop(info, "used_memory_human")),
                        sanitize(prop(info, "used_memory_peak_human"))
                );
                writeRow(row);
                log.info("[MemorySnapshot] {} bytes={} peak={} keys={}",
                        Instant.now(),
                        prop(info, "used_memory_human"),
                        prop(info, "used_memory_peak_human"),
                        dbSize);
            }
        } catch (Exception e) {
            log.warn("[MemorySnapshot] failed: {}", e.getMessage());
        }
    }

    private static String prop(Properties p, String key) {
        if (p == null) return "";
        Object v = p.get(key);
        return v == null ? "" : v.toString();
    }

    /** 字段里可能含逗号（人类可读字符串）会破坏 CSV，简单替换 */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace(',', '_');
    }

    private void writeRow(String row) throws IOException {
        Path p = Paths.get(csvPath);
        Path parent = p.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        boolean newFile = !Files.exists(p);
        try (var w = Files.newBufferedWriter(p,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (newFile) {
                w.write(CSV_HEADER);
                w.newLine();
            }
            w.write(row);
            w.newLine();
        }
    }
}
