package cn.edu.sztui.stream.healthcheck;

import cn.edu.sztui.stream.application.external.engine.ArticleUrlResolver;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.SourceConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfigLoader;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ContentParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult.InfoItemMeta;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl.SztuCmsListParser;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl.SztuGwtContentParser;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl.SztuGwtContentParser.TemplateFingerprint;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl.SztuGwtListParser;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

/**
 * 全量在线爬虫诊断 + 模板指纹分布统计。
 * <p>
 * 对每个公开源：爬列表 → 取前 N 条 → 爬详情 → 检查字段 + 记录命中的 CSS 选择器。
 * <p>
 * 末尾输出：
 * <ul>
 *   <li>源/文章计数（完整 / 外链 / 空 / 失败 分桶）</li>
 *   <li>模板指纹分布（title / meta / content 各选择器命中次数）</li>
 *   <li>未识别模板的源清单（真正需要补 selector 的位置）</li>
 * </ul>
 * <p>
 * 运行：
 * <pre>
 * ./gradlew :module-stream:test --tests "OnlineCrawlDiagnostic" --console=plain --info
 * # 只跑匹配的源（正则，作用于 source.id）：
 * ./gradlew :module-stream:test --tests "OnlineCrawlDiagnostic" -Ddiag.filter="kyb|sgim"
 * </pre>
 */
@Tag("online")
class OnlineCrawlDiagnostic {

    private static final int MAX_DETAIL_CHECK = 3;
    private static final int PARALLELISM = 8;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:148.0) Gecko/20100101 Firefox/148.0";

    private final SztuCmsListParser cmsListParser = new SztuCmsListParser();
    private final SztuGwtListParser gwtListParser = new SztuGwtListParser();
    private final SztuGwtContentParser gwtContentParser = new SztuGwtContentParser();
    private final CrawlerConfigLoader configLoader = new CrawlerConfigLoader();

    /** 单源诊断结果（为并发收集 + 有序打印服务）。 */
    private record SourceReport(
            SourceConfig source,
            String listUrl,
            String outcome,   // "ok" | "empty" | "fail"
            String failReason,
            int itemCount,
            List<ArticleReport> articles
    ) {}

    private record ArticleReport(
            int index,
            String title,
            String detailUrl,
            String status,    // "ok" | "external" | "http-error" | "parse-fail" | "incomplete" | "request-error"
            List<String> issues,
            TemplateFingerprint fingerprint,
            int contentLen
    ) {}

    @Test
    void runFullDiagnostic() throws Exception {
        configLoader.init();
        HttpClient httpClient = createInsecureHttpClient();

        Pattern filter = compileFilter(System.getProperty("diag.filter"));
        List<SourceConfig> publicSources = configLoader.getEnabledSources().stream()
                .filter(s -> !s.isRequiresAuth())
                .filter(s -> filter == null || filter.matcher(s.getId()).find())
                .toList();

        // 并发：每个源一个 task，结果按源顺序落位。
        Map<String, SourceReport> resultsById = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM);
        long startMs = System.currentTimeMillis();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (SourceConfig source : publicSources) {
                futures.add(pool.submit(() -> {
                    SourceReport r = diagnoseSource(httpClient, source);
                    resultsById.put(source.getId(), r);
                }));
            }
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdown();
        }

        long elapsedMs = System.currentTimeMillis() - startMs;

        // ==================== 渲染报告 ====================
        StringBuilder report = new StringBuilder();
        report.append("=== 全量在线诊断报告 ===\n");
        report.append("诊断时间: ").append(LocalDateTime.now()).append("\n");
        report.append("公开源数量: ").append(publicSources.size());
        if (filter != null) report.append(" (过滤: ").append(filter.pattern()).append(")");
        report.append("\n");
        report.append("耗时: ").append(elapsedMs).append(" ms (并发=")
                .append(PARALLELISM).append(")\n\n");

        // 按 config 顺序输出
        int okSources = 0, emptySources = 0, failSources = 0;
        int totalArticles = 0, okArticles = 0, externalArticles = 0;
        int missingTitle = 0, missingAuthor = 0, missingDate = 0, missingContent = 0, detail404 = 0, detailError = 0, parseFail = 0;
        Map<String, LongAdder> titleSelCounts = new ConcurrentHashMap<>();
        Map<String, LongAdder> metaSelCounts = new ConcurrentHashMap<>();
        Map<String, LongAdder> contentSelCounts = new ConcurrentHashMap<>();
        List<String> unknownTemplateSources = new ArrayList<>();
        int[] contentLenBuckets = new int[5]; // <50, <200, <500, <2000, >=2000

        for (SourceConfig source : publicSources) {
            SourceReport r = resultsById.get(source.getId());
            if (r == null) continue;
            report.append("──── ").append(source.getName())
                    .append(" (").append(source.getId())
                    .append(", parser=").append(source.getParserType())
                    .append(") ────\n");

            switch (r.outcome()) {
                case "ok" -> {
                    okSources++;
                    report.append("  列表页 OK: ").append(r.itemCount()).append(" 条  URL: ")
                            .append(r.listUrl()).append("\n");
                }
                case "empty" -> {
                    emptySources++;
                    report.append("  ⚠️  列表解析无结果: ").append(r.listUrl()).append("\n\n");
                    continue;
                }
                case "fail" -> {
                    failSources++;
                    report.append("  ❌ ").append(r.failReason()).append("\n\n");
                    continue;
                }
            }

            boolean hasUnknownTemplate = false;
            for (ArticleReport a : r.articles()) {
                totalArticles++;
                switch (a.status()) {
                    case "ok" -> {
                        okArticles++;
                        report.append("  [").append(a.index()).append("] ✅ ")
                                .append(a.title());
                        if (a.fingerprint() != null) report.append("  [").append(tag(a.fingerprint())).append("]");
                        report.append("\n");
                        accumulate(a.fingerprint(), titleSelCounts, metaSelCounts, contentSelCounts);
                        bucketize(a.contentLen(), contentLenBuckets);
                    }
                    case "external" -> {
                        externalArticles++;
                        report.append("  [").append(a.index()).append("] ⏭️  外链: ")
                                .append(a.title()).append(" | ").append(a.detailUrl()).append("\n");
                    }
                    case "incomplete" -> {
                        if (a.issues().contains("缺标题")) missingTitle++;
                        if (a.issues().contains("缺来源")) missingAuthor++;
                        if (a.issues().contains("缺日期")) missingDate++;
                        if (a.issues().stream().anyMatch(s -> s.startsWith("缺正文"))) missingContent++;
                        report.append("  [").append(a.index()).append("] ⚠️  ")
                                .append(String.join(", ", a.issues()))
                                .append(" | ").append(a.title())
                                .append(" | ").append(a.detailUrl());
                        if (a.fingerprint() != null) report.append("  [").append(tag(a.fingerprint())).append("]");
                        report.append("\n");
                        accumulate(a.fingerprint(), titleSelCounts, metaSelCounts, contentSelCounts);
                        bucketize(a.contentLen(), contentLenBuckets);
                        if (a.fingerprint() != null && (a.fingerprint().titleSelector() == null
                                || a.fingerprint().contentSelector() == null)) {
                            hasUnknownTemplate = true;
                        }
                    }
                    case "http-error" -> {
                        detail404++;
                        report.append("  [").append(a.index()).append("] ❌ ")
                                .append(String.join(", ", a.issues()))
                                .append(": ").append(a.detailUrl()).append("\n");
                    }
                    case "parse-fail" -> {
                        parseFail++;
                        report.append("  [").append(a.index()).append("] ⚠️  解析失败: ")
                                .append(String.join(", ", a.issues()))
                                .append(" | ").append(a.title())
                                .append(" | ").append(a.detailUrl()).append("\n");
                    }
                    case "request-error" -> {
                        detailError++;
                        report.append("  [").append(a.index()).append("] ❌ 请求异常: ")
                                .append(String.join(", ", a.issues()))
                                .append(" | ").append(a.detailUrl()).append("\n");
                    }
                }
            }
            if (hasUnknownTemplate) unknownTemplateSources.add(source.getId());
            report.append("\n");
        }

        // ==================== 汇总 ====================
        report.append("═══════════════════════════════════════\n");
        report.append("               汇  总\n");
        report.append("═══════════════════════════════════════\n");
        report.append("源统计:\n");
        report.append("  正常(有items): ").append(okSources).append("\n");
        report.append("  空(无items):   ").append(emptySources).append("\n");
        report.append("  失败(HTTP错误): ").append(failSources).append("\n");
        report.append("  总计:           ").append(publicSources.size()).append("\n\n");
        report.append("文章统计:\n");
        report.append("  完整(解析+字段齐全): ").append(okArticles).append("/").append(totalArticles).append("\n");
        report.append("  外链(短路不解析):   ").append(externalArticles).append("\n");
        report.append("  缺标题:   ").append(missingTitle).append("\n");
        report.append("  缺来源:   ").append(missingAuthor).append("\n");
        report.append("  缺日期:   ").append(missingDate).append("\n");
        report.append("  缺正文:   ").append(missingContent).append("\n");
        report.append("  解析失败: ").append(parseFail).append("\n");
        report.append("  详情HTTP错误: ").append(detail404).append("\n");
        report.append("  请求异常: ").append(detailError).append("\n\n");

        report.append("正文长度分布 (字符数):\n");
        report.append("  <50:     ").append(contentLenBuckets[0]).append("\n");
        report.append("  50~200:  ").append(contentLenBuckets[1]).append("\n");
        report.append("  200~500: ").append(contentLenBuckets[2]).append("\n");
        report.append("  500~2k:  ").append(contentLenBuckets[3]).append("\n");
        report.append("  ≥2k:     ").append(contentLenBuckets[4]).append("\n\n");

        report.append("模板指纹分布 (命中即按序采用，未列出的选择器=0 命中):\n");
        report.append("  标题选择器:\n");
        appendSorted(report, titleSelCounts);
        report.append("  元信息容器:\n");
        appendSorted(report, metaSelCounts);
        report.append("  正文选择器:\n");
        appendSorted(report, contentSelCounts);
        report.append("\n");

        if (!unknownTemplateSources.isEmpty()) {
            report.append("⚠️  未识别模板的源 (title 或 content 选择器为 null，需要补模板):\n");
            unknownTemplateSources.forEach(id -> report.append("  - ").append(id).append("\n"));
            report.append("\n");
        } else {
            report.append("✅ 所有源的 title + content 均被选择器回退链覆盖\n\n");
        }

        System.out.println(report);
        Path reportPath = Path.of("diagnostic-report.txt");
        Files.writeString(reportPath, report.toString());
        System.out.println("报告已写入: " + reportPath.toAbsolutePath());
    }

    // ==================== 单源诊断 ====================

    private SourceReport diagnoseSource(HttpClient httpClient, SourceConfig source) {
        String listUrl = buildListUrl(source);
        if (listUrl == null) {
            return new SourceReport(source, null, "fail", "无列表URL配置", 0, List.of());
        }
        try {
            HttpResponse<String> listResp = httpGet(httpClient, listUrl);
            if (listResp.statusCode() != 200) {
                return new SourceReport(source, listUrl, "fail",
                        "列表页 HTTP " + listResp.statusCode() + ": " + listUrl, 0, List.of());
            }
            ListParserResult listResult = parseList(source, listResp.body());
            if (listResult == null || listResult.getItems() == null || listResult.getItems().isEmpty()) {
                return new SourceReport(source, listUrl, "empty", null, 0, List.of());
            }

            List<ArticleReport> articles = new ArrayList<>();
            int itemCount = listResult.getItems().size();
            int checkCount = Math.min(MAX_DETAIL_CHECK, itemCount);
            for (int i = 0; i < checkCount; i++) {
                articles.add(diagnoseArticle(httpClient, source, listResult.getItems().get(i), i + 1));
            }
            return new SourceReport(source, listUrl, "ok", null, itemCount, articles);
        } catch (Exception e) {
            return new SourceReport(source, listUrl, "fail",
                    "源级异常: " + e.getClass().getSimpleName() + ": " + e.getMessage(), 0, List.of());
        }
    }

    private ArticleReport diagnoseArticle(HttpClient httpClient, SourceConfig source, InfoItemMeta item, int idx) {
        String detailUrl = resolveDetailUrl(source, item);
        if (detailUrl == null) {
            return new ArticleReport(idx, safeTitle(item), null, "external", List.of("无URL"), null, 0);
        }
        if (detailUrl.startsWith("EXTERNAL:") || ArticleUrlResolver.isExternalLink(detailUrl)) {
            return new ArticleReport(idx, safeTitle(item), detailUrl, "external", List.of(), null, 0);
        }
        try {
            HttpResponse<String> detailResp = httpGet(httpClient, detailUrl);
            if (detailResp.statusCode() != 200) {
                return new ArticleReport(idx, safeTitle(item), detailUrl, "http-error",
                        List.of("HTTP " + detailResp.statusCode()), null, 0);
            }
            ContentParserResult content = parseContent(source, detailResp.body(), item.getId());
            TemplateFingerprint fingerprint = gwtContentParser.detectTemplate(Jsoup.parse(detailResp.body()));

            if (content == null || !content.isSuccess()) {
                return new ArticleReport(idx, safeTitle(item), detailUrl, "parse-fail",
                        List.of(content != null ? content.getErrorMessage() : "null"), fingerprint, 0);
            }

            List<String> issues = new ArrayList<>();
            if (!StringUtils.hasText(content.getTitle())) issues.add("缺标题");
            if (!StringUtils.hasText(content.getAuthor())) issues.add("缺来源");
            if (!StringUtils.hasText(content.getPublishTime())) issues.add("缺日期");
            int contentLen = content.getContent() != null ? content.getContent().length() : 0;
            if (contentLen < 50) issues.add("缺正文(len=" + contentLen + ")");

            String title = StringUtils.hasText(content.getTitle()) ? content.getTitle() : safeTitle(item);
            return new ArticleReport(idx, title, detailUrl,
                    issues.isEmpty() ? "ok" : "incomplete", issues, fingerprint, contentLen);
        } catch (Exception e) {
            return new ArticleReport(idx, safeTitle(item), detailUrl, "request-error",
                    List.of(e.getClass().getSimpleName() + ": " + e.getMessage()), null, 0);
        }
    }

    // ==================== 指纹/统计辅助 ====================

    private static void accumulate(TemplateFingerprint fp,
                                   Map<String, LongAdder> titleCounts,
                                   Map<String, LongAdder> metaCounts,
                                   Map<String, LongAdder> contentCounts) {
        if (fp == null) return;
        incr(titleCounts, fp.titleSelector());
        incr(metaCounts, fp.metaContainer());
        incr(contentCounts, fp.contentSelector());
    }

    private static void incr(Map<String, LongAdder> m, String key) {
        m.computeIfAbsent(key == null ? "<none>" : key, k -> new LongAdder()).increment();
    }

    private static void bucketize(int len, int[] buckets) {
        if (len < 50) buckets[0]++;
        else if (len < 200) buckets[1]++;
        else if (len < 500) buckets[2]++;
        else if (len < 2000) buckets[3]++;
        else buckets[4]++;
    }

    private static void appendSorted(StringBuilder report, Map<String, LongAdder> counts) {
        counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, LongAdder>>comparingLong(e -> e.getValue().sum()).reversed())
                .forEach(e -> report.append("    ").append(padRight(e.getKey(), 45))
                        .append(" × ").append(e.getValue().sum()).append("\n"));
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    private static String tag(TemplateFingerprint fp) {
        return "T=" + abbrev(fp.titleSelector())
                + " M=" + abbrev(fp.metaContainer())
                + " C=" + abbrev(fp.contentSelector());
    }

    private static String abbrev(String sel) {
        if (sel == null) return "·";
        return sel.length() <= 20 ? sel : sel.substring(0, 17) + "…";
    }

    // ==================== 通用辅助 ====================

    private static Pattern compileFilter(String raw) {
        return StringUtils.hasText(raw) ? Pattern.compile(raw) : null;
    }

    private String buildListUrl(SourceConfig source) {
        if (StringUtils.hasText(source.getListUrl())) return source.getListUrl();
        if (StringUtils.hasText(source.getListUrlTemplate())) {
            return source.getListUrlTemplate().replace("{page}", "1").replace("{pageNum}", "1");
        }
        return null;
    }

    private String resolveDetailUrl(SourceConfig source, InfoItemMeta item) {
        String itemUrl = item.getUrl();
        if (StringUtils.hasText(itemUrl) && itemUrl.startsWith("http")) return itemUrl;
        String template = source.getDetailUrlTemplate();
        if (template != null && item.getId() != null) {
            String category = item.getCategoryCode();
            if (category == null) category = source.getCategoryCode();
            if (category == null) category = "";
            return template.replace("{id}", item.getId()).replace("{category}", category);
        }
        if (StringUtils.hasText(itemUrl)) return source.getBaseUrl() + "/" + itemUrl;
        return null;
    }

    private ListParserResult parseList(SourceConfig source, String html) {
        if ("sztu-gwt".equals(source.getParserType())) return gwtListParser.parseList(html, source, 1);
        return cmsListParser.parseList(html, source, 1);
    }

    private ContentParserResult parseContent(SourceConfig source, String html, String itemId) {
        return gwtContentParser.parseContent(html, source, itemId);
    }

    private String safeTitle(InfoItemMeta item) {
        return item.getTitle() != null ? item.getTitle() : "(无标题)";
    }

    private HttpResponse<String> httpGet(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .timeout(TIMEOUT)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient createInsecureHttpClient() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAll, new java.security.SecureRandom());
        return HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
