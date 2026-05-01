package cn.edu.sztui.stream.healthcheck;

import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl.SztuGwtContentParser;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl.SztuGwtContentParser.TemplateFingerprint;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 实验 1.2：选择器有序回退链的命中分布
 * <p>
 * {@link SztuGwtContentParser} 内部维护两条 ~20 项有序选择器链：
 * <ul>
 *   <li>{@code TITLE_SELECTORS}（23 项）—— 标题候选</li>
 *   <li>{@code META_CONTAINERS}（19 项）—— 作者/来源/时间元信息容器</li>
 * </ul>
 * 加上正文 selectors（共 10 项）。本测试离线扫 {@code infos/downloaded_pages/demos/*.html}
 * （约 534 个真实样本），对每个 doc 调 {@code detectTemplate}，统计每个 selector 在样本中
 * 第一次命中的次数，最终输出"命中数 → 累积覆盖率"分布表。
 * <p>
 * <b>论文价值</b>：把"18 候选"从空话变成具体数据——前 N 个可能就覆盖 90%+，剩下的是
 * 边缘模板兜底。这个分布本身就是个有论证价值的发现。
 * <p>
 * 不依赖 Spring 上下文（parser 类是无状态 @Component，可直接 {@code new}）。
 * <p>
 * 运行：{@code ./gradlew :module-stream:test --tests SelectorHitDistributionTest --info}
 */
@Tag("experiment")
class SelectorHitDistributionTest {

    private static final Path DEMOS_DIR = Paths.get("..", "infos", "downloaded_pages", "demos");

    private final SztuGwtContentParser parser = new SztuGwtContentParser();

    @Test
    void selectorHitDistribution() throws IOException {
        if (!Files.isDirectory(DEMOS_DIR)) {
            System.out.println("[SKIP] demos 目录不存在: " + DEMOS_DIR.toAbsolutePath());
            return;
        }

        // 顺序保留 = 选择器在 parser 链中的优先级顺序，方便看"前 N 项覆盖率"
        Map<String, AtomicLong> titleHits = new LinkedHashMap<>();
        Map<String, AtomicLong> metaHits = new LinkedHashMap<>();
        Map<String, AtomicLong> contentHits = new LinkedHashMap<>();
        AtomicLong totalFiles = new AtomicLong();
        AtomicLong parseFails = new AtomicLong();
        AtomicLong noTitleHit = new AtomicLong();
        AtomicLong noMetaHit = new AtomicLong();
        AtomicLong noContentHit = new AtomicLong();

        try (Stream<Path> files = Files.walk(DEMOS_DIR, 1)) {
            files.filter(p -> p.toString().endsWith(".html"))
                    .forEach(file -> {
                        totalFiles.incrementAndGet();
                        try {
                            String html = Files.readString(file, StandardCharsets.UTF_8);
                            Document doc = Jsoup.parse(html);
                            TemplateFingerprint fp = parser.detectTemplate(doc);

                            if (fp.titleSelector() != null) {
                                titleHits.computeIfAbsent(fp.titleSelector(), k -> new AtomicLong()).incrementAndGet();
                            } else {
                                noTitleHit.incrementAndGet();
                            }
                            if (fp.metaContainer() != null) {
                                metaHits.computeIfAbsent(fp.metaContainer(), k -> new AtomicLong()).incrementAndGet();
                            } else {
                                noMetaHit.incrementAndGet();
                            }
                            if (fp.contentSelector() != null) {
                                contentHits.computeIfAbsent(fp.contentSelector(), k -> new AtomicLong()).incrementAndGet();
                            } else {
                                noContentHit.incrementAndGet();
                            }
                        } catch (Exception e) {
                            parseFails.incrementAndGet();
                        }
                    });
        }

        StringBuilder out = new StringBuilder();
        out.append("\n").append("=".repeat(96)).append("\n");
        out.append("实验 1.2  选择器有序回退链命中分布\n");
        out.append("=".repeat(96)).append("\n");
        out.append(String.format("样本: %d 个 demo HTML  解析失败: %d  目录: %s%n%n",
                totalFiles.get(), parseFails.get(), DEMOS_DIR.toAbsolutePath()));

        printSection(out, "标题选择器（TITLE_SELECTORS）", titleHits, totalFiles.get(), noTitleHit.get());
        printSection(out, "元信息容器（META_CONTAINERS）", metaHits, totalFiles.get(), noMetaHit.get());
        printSection(out, "正文选择器", contentHits, totalFiles.get(), noContentHit.get());

        out.append("=".repeat(96)).append("\n");
        System.out.println(out);
    }

    private static void printSection(StringBuilder out, String title,
                                     Map<String, AtomicLong> hits, long total, long noHit) {
        out.append("\n").append(title).append("（按命中次数降序）\n");
        out.append("-".repeat(96)).append("\n");
        out.append(String.format("%4s  %6s  %7s  %s%n", "rank", "hits", "cum%", "selector"));
        out.append("-".repeat(96)).append("\n");

        // 按命中数降序排列
        List<Map.Entry<String, AtomicLong>> sorted = new ArrayList<>(hits.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()));

        long cum = 0;
        int rank = 0;
        for (var e : sorted) {
            rank++;
            long n = e.getValue().get();
            cum += n;
            out.append(String.format("%4d  %6d  %6.2f%%  %s%n",
                    rank, n, total == 0 ? 0 : 100.0 * cum / total, e.getKey()));
        }
        if (noHit > 0) {
            out.append(String.format("  --  %6d  %6s   <无命中>%n", noHit, "-"));
        }
        long matched = total - noHit;
        out.append(String.format("总命中: %d / %d = %.2f%%   未命中: %d (%.2f%%)%n",
                matched, total, total == 0 ? 0 : 100.0 * matched / total,
                noHit, total == 0 ? 0 : 100.0 * noHit / total));
    }
}
