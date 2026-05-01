package cn.edu.sztui.experiment;

import cn.edu.sztui.base.BaseMain;
import cn.edu.sztui.common.cache.util.CacheUtil;
import cn.edu.sztui.common.cache.redis.RedisKeyGenerator;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.ChannelConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.SourceConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfigLoader;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult.InfoItemMeta;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.redis.core.RedisTemplate;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 实验 1.1：数据源近 N 天活跃覆盖率（V2 — 基于实际产出文章）
 * <p>
 * <b>V1 用 lastCrawlTime 是错的</b>：那个字段记录"调度器跑过这个源"，无论是否
 * 拿到新内容都会更新。后端常开 → 所有源 ≤1d ≤7d ≤30d 数字相同，分不开。
 * <p>
 * <b>V2 改为按文章产出统计</b>：扫每频道 timeline ZSET，反序列化每条
 * InfoItemMeta，按 sourceId 聚合两个时间维度：
 * <ul>
 *   <li><b>publishDate</b>（学校原生发布日期，yyyy-MM-dd）—— 最准，反映"源头近 N 天有没有发新文章"</li>
 *   <li><b>crawledAt</b>（爬虫保存时间）—— 辅助参考；注意 initSource 全量爬历史会让所有 item 的 crawledAt 等于启动时间，重启 7 天内此字段不可信</li>
 * </ul>
 * <p>
 * 论文用主表请引用 publishDate 列。
 * <p>
 * 运行：{@code ./gradlew :main:test --tests SourceCoverageTest --info}
 */
@SpringBootTest(classes = BaseMain.class)
@Tag("experiment")
class SourceCoverageTest {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId ZONE = ZoneId.systemDefault();

    @Resource private CrawlerConfigLoader configLoader;
    @Resource private CacheUtil cacheUtil;
    @Resource private RedisKeyGenerator redisKeyGenerator;
    @Resource private RedisTemplate<String, Object> redisTemplate;

    /** 通过 YAML 路径推 group：crawler/{group}/{group}-sources.yml */
    private Map<String, String> loadSourceGroupMap() throws Exception {
        Map<String, String> map = new HashMap<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        var files = resolver.getResources("classpath:crawler/*/*-sources.yml");
        Yaml yaml = new Yaml();
        for (var res : files) {
            String path = res.getURL().getPath();
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
                if (!(root.get("sources") instanceof List<?> list)) continue;
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> m)) continue;
                    Object id = m.get("id");
                    if (id != null) map.put(id.toString(), group);
                }
            }
        }
        return map;
    }

    /** 解析 publishDate 字符串到 epoch millis；解析失败返回 null。容忍多种常见格式。 */
    private static Long parsePublishDate(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.trim();
        try {
            // yyyy-MM-dd
            if (s.length() >= 10 && s.charAt(4) == '-') {
                LocalDate d = LocalDate.parse(s.substring(0, 10), DATE_FMT);
                return d.atStartOfDay(ZONE).toInstant().toEpochMilli();
            }
            // yyyy/MM/dd
            if (s.length() >= 10 && s.charAt(4) == '/') {
                LocalDate d = LocalDate.parse(s.substring(0, 10),
                        DateTimeFormatter.ofPattern("yyyy/MM/dd"));
                return d.atStartOfDay(ZONE).toInstant().toEpochMilli();
            }
        } catch (Exception ignore) {}
        return null;
    }

    @Test
    void coverageReport() throws Exception {
        Map<String, String> sourceGroup = loadSourceGroupMap();
        List<SourceConfig> sources = configLoader.getSources();

        long now = System.currentTimeMillis();
        long t1d = now - DAY_MS;
        long t7d = now - 7 * DAY_MS;
        long t30d = now - 30 * DAY_MS;

        // sourceId → counters by publishDate
        // [total_items, with_publishDate, items_30d, items_7d, items_1d]
        Map<String, long[]> bySrcPubItems = new HashMap<>();
        // sourceId → counters by crawledAt
        // [items_30d, items_7d, items_1d]
        Map<String, long[]> bySrcCrawledItems = new HashMap<>();

        // 列出所有频道，扫每个 info:{ch}:timeline 的所有 ID，然后批量读 meta hash
        List<ChannelConfig> channels = configLoader.getChannels();
        long totalItems = 0;
        for (ChannelConfig ch : channels) {
            String chId = ch.getId();
            String tlKey = redisKeyGenerator.generate("info:" + chId + ":timeline");
            String metaKey = redisKeyGenerator.generate("info:" + chId + ":meta");

            Set<Object> ids = redisTemplate.opsForZSet().reverseRange(tlKey, 0, -1);
            if (ids == null || ids.isEmpty()) continue;

            for (Object idObj : ids) {
                Object metaJson = cacheUtil.hget(metaKey, idObj.toString());
                if (metaJson == null) continue;
                InfoItemMeta meta;
                try {
                    meta = JSON.parseObject(metaJson.toString(), InfoItemMeta.class);
                } catch (Exception e) {
                    continue;
                }
                if (meta == null || meta.getSourceId() == null) continue;
                totalItems++;
                String sid = meta.getSourceId();
                long[] pub = bySrcPubItems.computeIfAbsent(sid, k -> new long[5]);
                pub[0]++;

                Long pubMs = parsePublishDate(meta.getPublishDate());
                if (pubMs != null) {
                    pub[1]++;
                    if (pubMs >= t30d) pub[2]++;
                    if (pubMs >= t7d)  pub[3]++;
                    if (pubMs >= t1d)  pub[4]++;
                }

                Long cMs = meta.getCrawledAt();
                if (cMs != null) {
                    long[] cr = bySrcCrawledItems.computeIfAbsent(sid, k -> new long[3]);
                    if (cMs >= t30d) cr[0]++;
                    if (cMs >= t7d)  cr[1]++;
                    if (cMs >= t1d)  cr[2]++;
                }
            }
        }

        // group-level aggregation
        // [total_sources, src_with_any_item, src_pub_30d, src_pub_7d, src_pub_1d, src_crawl_7d]
        Map<String, long[]> grp = new TreeMap<>();
        List<String> noOutput7d = new ArrayList<>();

        for (SourceConfig src : sources) {
            String g = sourceGroup.getOrDefault(src.getId(), "unknown");
            long[] gc = grp.computeIfAbsent(g, k -> new long[6]);
            gc[0]++;
            long[] pub = bySrcPubItems.get(src.getId());
            long[] cr  = bySrcCrawledItems.get(src.getId());
            boolean hasItem  = pub != null && pub[0] > 0;
            boolean pub30d   = pub != null && pub[2] > 0;
            boolean pub7d    = pub != null && pub[3] > 0;
            boolean pub1d    = pub != null && pub[4] > 0;
            boolean crawl7d  = cr  != null && cr[1]  > 0;
            if (hasItem) gc[1]++;
            if (pub30d)  gc[2]++;
            if (pub7d)   gc[3]++;
            if (pub1d)   gc[4]++;
            if (crawl7d) gc[5]++;
            if (!pub7d && !crawl7d) {
                noOutput7d.add(g + " / " + src.getId() + " (" + src.getName() + ")");
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("\n").append("=".repeat(96)).append("\n");
        out.append("实验 1.1  数据源活跃覆盖率（V2 — 基于实际文章产出）\n");
        out.append("=".repeat(96)).append("\n");
        out.append(String.format("总源数（enabled）: %d   总文章数（已落 Redis）: %d   观测时刻: %s%n%n",
                sources.size(), totalItems, new Date(now)));

        out.append("【主表】按学校原生 publishDate 判定（推荐论文引用）\n");
        out.append(String.format("%-12s %6s %8s %10s %10s %10s %12s%n",
                "group", "total", "ever", "pub<=30d", "pub<=7d", "pub<=1d", "crawl<=7d"));
        out.append("-".repeat(96)).append("\n");
        long[] sum = new long[6];
        for (var e : grp.entrySet()) {
            long[] c = e.getValue();
            for (int i = 0; i < 6; i++) sum[i] += c[i];
            out.append(String.format("%-12s %6d %8d %10d %10d %10d %12d%n",
                    e.getKey(), c[0], c[1], c[2], c[3], c[4], c[5]));
        }
        out.append("-".repeat(96)).append("\n");
        out.append(String.format("%-12s %6d %8d %10d %10d %10d %12d%n",
                "TOTAL", sum[0], sum[1], sum[2], sum[3], sum[4], sum[5]));

        if (sum[0] > 0) {
            out.append("\n覆盖率（基于 publishDate）：\n");
            out.append(String.format("  曾产出文章        : %d / %d = %.2f%%%n", sum[1], sum[0], 100.0*sum[1]/sum[0]));
            out.append(String.format("  近 30 天有新文     : %d / %d = %.2f%%%n", sum[2], sum[0], 100.0*sum[2]/sum[0]));
            out.append(String.format("  近 7 天有新文      : %d / %d = %.2f%%%n", sum[3], sum[0], 100.0*sum[3]/sum[0]));
            out.append(String.format("  近 1 天有新文      : %d / %d = %.2f%%%n", sum[4], sum[0], 100.0*sum[4]/sum[0]));
            out.append(String.format("  近 7 天 crawledAt  : %d / %d = %.2f%%（参考）%n",
                    sum[5], sum[0], 100.0*sum[5]/sum[0]));
        }

        if (!noOutput7d.isEmpty()) {
            out.append(String.format("%n近 7 天既无新文又无新爬取的源（%d 个）：%n", noOutput7d.size()));
            for (String s : noOutput7d) out.append("  - ").append(s).append("\n");
        }
        out.append("=".repeat(96)).append("\n");
        System.out.println(out);
    }
}
