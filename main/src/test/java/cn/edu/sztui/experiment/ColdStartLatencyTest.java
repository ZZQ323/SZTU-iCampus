package cn.edu.sztui.experiment;

import cn.edu.sztui.base.BaseMain;
import cn.edu.sztui.stream.application.external.engine.CrawlEngine;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.SourceConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfigLoader;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 实验 3.1：冷启动（阶段 1）时延分布
 * <p>
 * 度量 {@link CrawlEngine#initSource} 从被调用到首页 items 落 Redis +
 * {@code markSourceInitialized} 写入完成的总时长，即论文 §3.2.4 节所声称
 * "5 秒内用户可见" 的硬指标。
 * <p>
 * 测试实现策略（避开真重启的不可重复性）：
 * <ol>
 *   <li>选 N 个公开源（{@code requiresAuth=false}），按 group 分布抽样 </li>
 *   <li>对每个源：先 {@code clearSourceInitialized} 把它置回未初始化状态，
 *       然后调用 {@code initSource}，记录 wall clock 时长</li>
 *   <li>每源重复 R 次取分布；总请求数 = N × R，对学校友好上限 ~30 次</li>
 *   <li>报告：p50 / p95 / p99 / 最大 / 失败数；按 group 拆分</li>
 * </ol>
 * <p>
 * 阶段 2（异步爬剩余页）不在本实验测量范围 —— 用户可见性的硬约束在阶段 1。
 * <p>
 * 配置（命令行 -D 覆盖）：
 * <ul>
 *   <li>{@code -Dexp31.samples=10}    每 group 抽样源数（默认 2）</li>
 *   <li>{@code -Dexp31.repeats=3}     每源重复次数（默认 1）</li>
 *   <li>{@code -Dexp31.dryrun=true}   不真打学校，只列采样源</li>
 * </ul>
 * <p>
 * 运行：{@code ./gradlew :main:test --tests ColdStartLatencyTest --info}
 */
@SpringBootTest(classes = BaseMain.class)
@Tag("experiment")
class ColdStartLatencyTest {

    @Resource private CrawlEngine crawlEngine;
    @Resource private CrawlerConfigLoader configLoader;
    @Resource private InfoCacheUtil infoCacheUtil;

    @Test
    void coldStartLatencyDistribution() {
        int samplesPerGroup = Integer.getInteger("exp31.samples", 2);
        int repeats = Integer.getInteger("exp31.repeats", 1);
        boolean dryRun = Boolean.parseBoolean(System.getProperty("exp31.dryrun", "false"));

        List<SourceConfig> all = configLoader.getEnabledSources();

        Map<String, List<SourceConfig>> byGroup = new TreeMap<>();
        for (SourceConfig s : all) {
            if (s.isRequiresAuth()) continue;
            if (!"sztu-cms".equals(s.getParserType())
                    && !"sztu-gwt".equals(s.getParserType())) continue;
            String g = guessGroup(s.getChannelId());
            byGroup.computeIfAbsent(g, k -> new ArrayList<>()).add(s);
        }

        Random rnd = new Random(20260430L);
        List<SourceConfig> picked = new ArrayList<>();
        for (var e : byGroup.entrySet()) {
            List<SourceConfig> list = new ArrayList<>(e.getValue());
            Collections.shuffle(list, rnd);
            for (int i = 0; i < Math.min(samplesPerGroup, list.size()); i++) {
                picked.add(list.get(i));
            }
        }

        StringBuilder hdr = new StringBuilder();
        hdr.append("\n").append("=".repeat(80)).append("\n");
        hdr.append("实验 3.1  冷启动阶段 1 时延（initSource 首页爬取 → 落 Redis）\n");
        hdr.append("=".repeat(80)).append("\n");
        hdr.append(String.format("采样: %d 个 group × %d 个源 × %d 次 = %d 次实际调用%n",
                byGroup.size(), samplesPerGroup, repeats, picked.size() * repeats));
        hdr.append(String.format("dryRun=%s%n", dryRun));
        hdr.append("---- 抽样列表 ----\n");
        for (SourceConfig s : picked) {
            hdr.append(String.format("  [%s] %s  (%s)%n",
                    guessGroup(s.getChannelId()), s.getId(), s.getName()));
        }
        System.out.println(hdr);
        if (dryRun) return;

        Map<String, List<Long>> byGroupMs = new TreeMap<>();
        List<long[]> records = new ArrayList<>();
        int failCount = 0;

        for (SourceConfig src : picked) {
            String g = guessGroup(src.getChannelId());
            byGroupMs.computeIfAbsent(g, k -> new ArrayList<>());
            for (int r = 0; r < repeats; r++) {
                infoCacheUtil.clearSourceInitialized(src.getId());
                long t0 = System.currentTimeMillis();
                boolean ok = true;
                try {
                    crawlEngine.initSource(src.getId(), null);
                } catch (Exception e) {
                    ok = false;
                }
                long ms = System.currentTimeMillis() - t0;
                if (ok && infoCacheUtil.isSourceInitialized(src.getId())) {
                    records.add(new long[]{ms, 1});
                    byGroupMs.get(g).add(ms);
                } else {
                    records.add(new long[]{ms, 0});
                    failCount++;
                }
                System.out.printf("  %-30s [%s] r%d  %dms  %s%n",
                        src.getId(), g, r, ms, ok ? "OK" : "FAIL");
            }
        }

        List<Long> all_ok_ms = records.stream()
                .filter(r -> r[1] == 1)
                .map(r -> r[0])
                .sorted()
                .collect(Collectors.toList());

        StringBuilder out = new StringBuilder();
        out.append("\n").append("-".repeat(80)).append("\n");
        out.append("阶段 1 时延全局分布（成功样本）\n");
        out.append("-".repeat(80)).append("\n");
        if (all_ok_ms.isEmpty()) {
            out.append("无成功样本\n");
        } else {
            out.append(String.format("n=%d  失败=%d%n", all_ok_ms.size(), failCount));
            out.append(String.format("min   = %d ms%n", all_ok_ms.get(0)));
            out.append(String.format("p50   = %d ms%n", percentile(all_ok_ms, 50)));
            out.append(String.format("p95   = %d ms%n", percentile(all_ok_ms, 95)));
            out.append(String.format("p99   = %d ms%n", percentile(all_ok_ms, 99)));
            out.append(String.format("max   = %d ms%n", all_ok_ms.get(all_ok_ms.size() - 1)));
            out.append(String.format("mean  = %.0f ms%n", all_ok_ms.stream().mapToLong(Long::longValue).average().orElse(0)));
            long under5s = all_ok_ms.stream().filter(v -> v <= 5000).count();
            out.append(String.format("≤5s   = %d / %d = %.1f%%  ←  论文 §3.2.4 承诺值%n",
                    under5s, all_ok_ms.size(), 100.0 * under5s / all_ok_ms.size()));
        }

        out.append("\n按 group 中位数:\n");
        for (var e : byGroupMs.entrySet()) {
            List<Long> sorted = new ArrayList<>(e.getValue());
            Collections.sort(sorted);
            if (sorted.isEmpty()) {
                out.append(String.format("  %-12s : 无成功样本%n", e.getKey()));
            } else {
                out.append(String.format("  %-12s : n=%d  p50=%dms  max=%dms%n",
                        e.getKey(), sorted.size(),
                        percentile(sorted, 50), sorted.get(sorted.size() - 1)));
            }
        }
        out.append("=".repeat(80)).append("\n");
        System.out.println(out);
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return -1;
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static String guessGroup(String channelId) {
        if (channelId == null) return "unknown";
        if (channelId.startsWith("college-")) return "college";
        if (channelId.startsWith("dept-"))    return "department";
        if (channelId.startsWith("lab-"))     return "support";
        switch (channelId) {
            case "announcement": case "news":
            case "campus-life": case "admission":
                return "official";
            case "academic": case "job":
                return "fixed";
            default:
                return "unknown";
        }
    }
}
