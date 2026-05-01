package cn.edu.sztui.stream.healthcheck;

import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.SourceConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfigLoader;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

/**
 * 实验 1.1：数据源近 N 天活跃覆盖率
 * <p>
 * 直接读 Redis 里 {@code icampus:cache:info:source:{sourceId}:system} 的
 * {@code lastCrawlTime} 字段，与 sources.yml 配置中的源 ID 做交集统计。
 * <p>
 * "活跃" 定义：lastCrawlTime 在过去 N 天内被更新过（CrawlEngine 在每次成功
 * 列表爬取后写入此字段，无论是否拿到新条目）。这是"调度器近期跑过"的硬证据。
 * <p>
 * 输出（控制台 + System.out）：
 * <ul>
 *   <li>各 group（fixed/official/department/support/college）的源数量与活跃源数量</li>
 *   <li>近 1d / 7d / 30d / 全部时间的覆盖率分布</li>
 *   <li>从未爬过的源列表（debug 用）</li>
 * </ul>
 * <p>
 * 运行：{@code ./gradlew :module-stream:test --tests SourceCoverageTest --info}
 * 需要 Redis 已被 module-stream 跑起来填充过数据。
 */
@SpringBootTest(classes = SourceCoverageTest.TestApp.class)
@Tag("experiment")
class SourceCoverageTest {

    @SpringBootApplication(scanBasePackages = "cn.edu.sztui")
    static class TestApp {}

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    @Resource
    private CrawlerConfigLoader configLoader;

    @Resource
    private InfoCacheUtil infoCacheUtil;

    /** 通过 YAML 文件路径推出 group：crawler/{group}/{group}-sources.yml */
    private Map<String, String> loadSourceGroupMap() throws Exception {
        Map<String, String> map = new HashMap<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        org.springframework.core.io.Resource[] files = resolver.getResources("classpath:crawler/*/*-sources.yml");
        Yaml yaml = new Yaml();
        for (org.springframework.core.io.Resource res : files) {
            String path = res.getURL().getPath();
            // 形如 .../crawler/college/college-sources.yml
            String group = "unknown";
            int idx = path.lastIndexOf("/crawler/");
            if (idx >= 0) {
                String tail = path.substring(idx + "/crawler/".length());
                int slash = tail.indexOf('/');
                if (slash > 0) group = tail.substring(0, slash);
            }
            try (InputStream in = res.getInputStream()) {
                Map<String, Object> root = yaml.load(in);
                if (root == null) continue;
                Object srcObj = root.get("sources");
                if (!(srcObj instanceof List<?> list)) continue;
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> m)) continue;
                    Object id = m.get("id");
                    if (id != null) map.put(id.toString(), group);
                }
            }
        }
        return map;
    }

    @Test
    void coverageReport() throws Exception {
        Map<String, String> sourceGroup = loadSourceGroupMap();
        List<SourceConfig> sources = configLoader.getSources();

        long now = System.currentTimeMillis();
        long t1d = now - DAY_MS;
        long t7d = now - 7 * DAY_MS;
        long t30d = now - 30 * DAY_MS;

        // group → counters
        Map<String, int[]> stats = new TreeMap<>();
        // counters: [total, ever, 30d, 7d, 1d, initialized]
        List<String> neverList = new ArrayList<>();

        for (SourceConfig src : sources) {
            String group = sourceGroup.getOrDefault(src.getId(), "unknown");
            int[] c = stats.computeIfAbsent(group, k -> new int[6]);
            c[0]++;
            Long last = infoCacheUtil.getLastCrawlTime(src.getId());
            if (last != null) {
                c[1]++;
                if (last >= t30d) c[2]++;
                if (last >= t7d) c[3]++;
                if (last >= t1d) c[4]++;
            } else {
                neverList.add(group + " / " + src.getId() + " (" + src.getName() + ")");
            }
            if (infoCacheUtil.isSourceInitialized(src.getId())) c[5]++;
        }

        // 汇总打印
        StringBuilder out = new StringBuilder();
        out.append("\n").append("=".repeat(80)).append("\n");
        out.append("实验 1.1  数据源活跃覆盖率（基于 Redis lastCrawlTime）\n");
        out.append("=".repeat(80)).append("\n");
        out.append(String.format("总配置源（enabled）: %d%n", sources.size()));
        out.append(String.format("观测时刻: %s%n%n", new Date(now)));
        out.append(String.format("%-12s %6s %8s %8s %8s %8s %10s%n",
                "group", "total", "ever", "<=30d", "<=7d", "<=1d", "initialized"));
        out.append("-".repeat(80)).append("\n");
        int[] sum = new int[6];
        for (var e : stats.entrySet()) {
            int[] c = e.getValue();
            for (int i = 0; i < 6; i++) sum[i] += c[i];
            out.append(String.format("%-12s %6d %8d %8d %8d %8d %10d%n",
                    e.getKey(), c[0], c[1], c[2], c[3], c[4], c[5]));
        }
        out.append("-".repeat(80)).append("\n");
        out.append(String.format("%-12s %6d %8d %8d %8d %8d %10d%n",
                "TOTAL", sum[0], sum[1], sum[2], sum[3], sum[4], sum[5]));
        if (sum[0] > 0) {
            out.append("\n覆盖率：\n");
            out.append(String.format("  曾爬过 (ever)     : %d / %d = %.2f%%%n",
                    sum[1], sum[0], 100.0 * sum[1] / sum[0]));
            out.append(String.format("  近 30 天活跃      : %d / %d = %.2f%%%n",
                    sum[2], sum[0], 100.0 * sum[2] / sum[0]));
            out.append(String.format("  近 7 天活跃       : %d / %d = %.2f%%%n",
                    sum[3], sum[0], 100.0 * sum[3] / sum[0]));
            out.append(String.format("  近 1 天活跃       : %d / %d = %.2f%%%n",
                    sum[4], sum[0], 100.0 * sum[4] / sum[0]));
            out.append(String.format("  已初始化标记      : %d / %d = %.2f%%%n",
                    sum[5], sum[0], 100.0 * sum[5] / sum[0]));
        }
        if (!neverList.isEmpty()) {
            out.append("\n从未爬过的源（debug 用）:\n");
            for (String s : neverList) out.append("  - ").append(s).append("\n");
        }
        out.append("=".repeat(80)).append("\n");
        System.out.println(out);
    }
}
