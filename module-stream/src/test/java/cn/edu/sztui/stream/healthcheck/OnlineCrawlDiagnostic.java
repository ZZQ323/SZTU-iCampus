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
import java.util.LinkedHashMap;
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

        // ==================== 失败分类表 ====================
        report.append("═══════════════════════════════════════\n");
        report.append("            失败分类表\n");
        report.append("═══════════════════════════════════════\n");
        report.append("按失败类型聚合源，修一个类型即可批量止损。\n\n");

        LinkedHashMap<String, List<String>> byCategory = new LinkedHashMap<>();
        byCategory.put("LIST_NO_URL",        new ArrayList<>());  // YAML 未配 listUrl
        byCategory.put("LIST_HTTP_ERROR",    new ArrayList<>());  // 列表页 HTTP 4xx/5xx
        byCategory.put("LIST_EMPTY",         new ArrayList<>());  // 列表 200 但解析 0 条
        byCategory.put("DETAIL_HTTP_ERROR",  new ArrayList<>());  // 抽查详情 HTTP 错误 ≥1
        byCategory.put("DETAIL_REQUEST_ERROR", new ArrayList<>()); // 抽查详情网络异常 ≥1
        byCategory.put("DETAIL_PARSE_FAIL",  new ArrayList<>());  // 抽查详情解析失败 ≥1
        byCategory.put("NO_TITLE_MOST",      new ArrayList<>());  // ≥半数抽查缺标题
        byCategory.put("NO_DATE_MOST",       new ArrayList<>());  // ≥半数抽查缺日期
        byCategory.put("NO_AUTHOR_MOST",     new ArrayList<>());  // ≥半数抽查缺来源
        byCategory.put("NO_CONTENT_MOST",    new ArrayList<>());  // ≥半数抽查正文过短

        for (SourceConfig source : publicSources) {
            SourceReport r = resultsById.get(source.getId());
            if (r == null) continue;
            String idLine = String.format("%-24s %s", source.getId(), r.listUrl() != null ? r.listUrl() : "");

            if ("fail".equals(r.outcome())) {
                if (r.failReason() != null && r.failReason().contains("无列表URL")) {
                    byCategory.get("LIST_NO_URL").add(idLine);
                } else {
                    byCategory.get("LIST_HTTP_ERROR").add(idLine + "  [" + r.failReason() + "]");
                }
                continue;
            }
            if ("empty".equals(r.outcome())) {
                byCategory.get("LIST_EMPTY").add(idLine);
                continue;
            }

            // 对"ok"源扫描其抽查文章
            int checked = 0, httpErrN = 0, reqErrN = 0, parseFailN = 0;
            int noTitle = 0, noDate = 0, noAuthor = 0, noContent = 0;
            for (ArticleReport a : r.articles()) {
                if ("external".equals(a.status())) continue;  // 外链不纳入字段统计
                checked++;
                switch (a.status()) {
                    case "http-error" -> httpErrN++;
                    case "request-error" -> reqErrN++;
                    case "parse-fail" -> parseFailN++;
                    case "incomplete" -> {
                        if (a.issues().contains("缺标题")) noTitle++;
                        if (a.issues().contains("缺日期")) noDate++;
                        if (a.issues().contains("缺来源")) noAuthor++;
                        if (a.issues().stream().anyMatch(s -> s.startsWith("缺正文"))) noContent++;
                    }
                    default -> {}
                }
            }
            if (httpErrN > 0)
                byCategory.get("DETAIL_HTTP_ERROR").add(idLine + "  [" + httpErrN + "/" + checked + " 篇 HTTP 错误]");
            if (reqErrN > 0)
                byCategory.get("DETAIL_REQUEST_ERROR").add(idLine + "  [" + reqErrN + "/" + checked + " 篇请求异常]");
            if (parseFailN > 0)
                byCategory.get("DETAIL_PARSE_FAIL").add(idLine + "  [" + parseFailN + "/" + checked + " 篇解析失败]");
            // "多数缺失"阈值：抽查 ≥ 2 篇时 ≥ 半数；只 1 篇时 = 1
            int threshold = Math.max(1, (checked + 1) / 2);
            if (checked > 0 && noTitle   >= threshold) byCategory.get("NO_TITLE_MOST").add(idLine);
            if (checked > 0 && noDate    >= threshold) byCategory.get("NO_DATE_MOST").add(idLine);
            if (checked > 0 && noAuthor  >= threshold) byCategory.get("NO_AUTHOR_MOST").add(idLine);
            if (checked > 0 && noContent >= threshold) byCategory.get("NO_CONTENT_MOST").add(idLine);
        }

        Map<String, String> categoryDesc = Map.of(
                "LIST_NO_URL",         "YAML 未配 listUrl",
                "LIST_HTTP_ERROR",     "列表页 HTTP 4xx/5xx",
                "LIST_EMPTY",          "列表 200 但 parser 提取 0 条",
                "DETAIL_HTTP_ERROR",   "抽查详情 HTTP 4xx/5xx",
                "DETAIL_REQUEST_ERROR","抽查详情网络异常(ConnectException/超时)",
                "DETAIL_PARSE_FAIL",   "详情 parser 返回 success=false",
                "NO_TITLE_MOST",       "≥半数抽查缺标题",
                "NO_DATE_MOST",        "≥半数抽查缺日期",
                "NO_AUTHOR_MOST",      "≥半数抽查缺来源(页面本身常无作者)",
                "NO_CONTENT_MOST",    "≥半数抽查正文 <50 字"
        );
        for (Map.Entry<String, List<String>> e : byCategory.entrySet()) {
            List<String> list = e.getValue();
            report.append("▶ ").append(e.getKey())
                    .append(" (").append(categoryDesc.get(e.getKey())).append(") — ")
                    .append(list.size()).append(" 个源\n");
            if (list.isEmpty()) {
                report.append("  (无)\n");
            } else {
                list.forEach(s -> report.append("  - ").append(s).append("\n"));
            }
            report.append("\n");
        }

        System.out.println(report);
        Path reportPath = Path.of("diagnostic-report.txt");
        Files.writeString(reportPath, report.toString());
        System.out.println("报告已写入: " + reportPath.toAbsolutePath());

        // ==================== 评估表 CSV ====================
        Path csvPath = Path.of("url-audit.csv");
        writeAuditCsv(publicSources, resultsById, csvPath);
        System.out.println("评估表已写入: " + csvPath.toAbsolutePath());
    }

    /**
     * 产出 url-audit.csv 评估表，每个源一行。
     * 前 9 列由诊断预填（列表/详情状态、主要问题、建议行动），
     * 后 2 列 userDecision / userNote 留给人工标注。
     * 按"需要关注优先级"排序：列表失败 → 详情失败 → 字段缺失 → OK。
     * <p>
     * 使用闭环：本地跑诊断 → 打开 CSV 手工填 decision → 发给开发批量应用。
     */
    private void writeAuditCsv(List<SourceConfig> sources,
                               Map<String, SourceReport> resultsById,
                               Path path) {
        List<String[]> rows = new ArrayList<>();
        for (SourceConfig s : sources) {
            SourceReport r = resultsById.get(s.getId());
            if (r == null) continue;

            // 1. 源信息
            String id = s.getId();
            String name = s.getName() != null ? s.getName() : "";
            String cat = classifyByName(name);
            String listUrl = r.listUrl() != null ? r.listUrl() : "";

            // 2. 列表结果
            String listStatus;
            String listReason = "";
            int itemsFound = r.itemCount();
            switch (r.outcome()) {
                case "ok"    -> listStatus = "OK";
                case "empty" -> { listStatus = "LIST_EMPTY"; listReason = "200 但 parser 提取 0 条"; }
                case "fail"  -> {
                    listStatus = "LIST_FAIL";
                    listReason = r.failReason() != null ? r.failReason() : "";
                }
                default      -> listStatus = "UNKNOWN";
            }

            // 3. 详情统计
            int checked = 0, httpErr = 0, reqErr = 0, parseFail = 0, external = 0;
            int noTitle = 0, noDate = 0, noAuthor = 0, noContent = 0, okCount = 0;
            for (ArticleReport a : r.articles()) {
                if ("external".equals(a.status())) { external++; continue; }
                checked++;
                switch (a.status()) {
                    case "ok"            -> okCount++;
                    case "http-error"    -> httpErr++;
                    case "request-error" -> reqErr++;
                    case "parse-fail"    -> parseFail++;
                    case "incomplete"    -> {
                        if (a.issues().contains("缺标题")) noTitle++;
                        if (a.issues().contains("缺日期")) noDate++;
                        if (a.issues().contains("缺来源")) noAuthor++;
                        if (a.issues().stream().anyMatch(i -> i.startsWith("缺正文"))) noContent++;
                    }
                    default -> {}
                }
            }
            String detailRatio = checked > 0
                    ? String.format("%d/%d", okCount, checked)
                    : (external > 0 ? String.format("外链 %d", external) : "-");

            // 4. 主要问题 + 建议行动（按严重程度递减）
            int threshold = Math.max(1, (checked + 1) / 2);
            String primaryIssue;
            String suggestedAction;
            int priority;
            if ("LIST_FAIL".equals(listStatus)) {
                if (listReason.contains("无列表URL")) {
                    primaryIssue = "LIST_NO_URL";
                    suggestedAction = "补 listUrl 配置";
                    priority = 1;
                } else if (listReason.contains("404")) {
                    primaryIssue = "LIST_HTTP_404";
                    suggestedAction = "改 YAML listUrl 或 drop";
                    priority = 2;
                } else if (listReason.contains("503")) {
                    primaryIssue = "LIST_HTTP_503";
                    suggestedAction = "等服务端恢复后重跑";
                    priority = 3;
                } else {
                    primaryIssue = "LIST_HTTP_ERROR";
                    suggestedAction = "查服务端状态";
                    priority = 2;
                }
            } else if ("LIST_EMPTY".equals(listStatus)) {
                primaryIssue = "LIST_EMPTY";
                suggestedAction = "补 list parser 变体 或 drop（JS 渲染/结构特殊）";
                priority = 4;
            } else if (checked > 0 && httpErr >= threshold) {
                primaryIssue = "DETAIL_HTTP_ERROR_MOST";
                suggestedAction = "文章 URL 拼接错 或 域名受限";
                priority = 5;
            } else if (checked > 0 && reqErr >= threshold) {
                primaryIssue = "DETAIL_REQUEST_ERROR_MOST";
                suggestedAction = "域名不可达（可能仅内网/WebVPN）";
                priority = 5;
            } else if (checked > 0 && parseFail >= threshold) {
                primaryIssue = "DETAIL_PARSE_FAIL_MOST";
                suggestedAction = "补 content parser 变体";
                priority = 5;
            } else if (checked > 0 && noTitle >= threshold) {
                primaryIssue = "NO_TITLE_MOST";
                suggestedAction = "补 content parser 的 title 选择器";
                priority = 6;
            } else if (checked > 0 && noContent >= threshold) {
                primaryIssue = "NO_CONTENT_MOST";
                suggestedAction = "补 content parser 的正文选择器";
                priority = 6;
            } else if (checked > 0 && httpErr > 0 || reqErr > 0 || parseFail > 0) {
                primaryIssue = "DETAIL_PARTIAL_FAIL";
                suggestedAction = "少量抽查失败，抽更多条再判断";
                priority = 7;
            } else if (checked > 0 && noDate >= threshold && noAuthor >= threshold) {
                primaryIssue = "NO_DATE_AND_AUTHOR";
                suggestedAction = "页面本身无字段，可忽略或用兜底";
                priority = 8;
            } else if (checked > 0 && noDate >= threshold) {
                primaryIssue = "NO_DATE_MOST";
                suggestedAction = "可忽略（多数页面无日期）或用列表 publishDate 兜底";
                priority = 9;
            } else if (checked > 0 && noAuthor >= threshold) {
                primaryIssue = "NO_AUTHOR_MOST";
                suggestedAction = "可忽略（页面本身常无作者）";
                priority = 9;
            } else if (checked == 0 && external > 0) {
                primaryIssue = "OK_ALL_EXTERNAL";
                suggestedAction = "全外链，短路处理正常";
                priority = 10;
            } else {
                primaryIssue = "OK";
                suggestedAction = "保留";
                priority = 11;
            }

            rows.add(new String[]{
                    String.valueOf(priority),    // 0 用于排序，不输出
                    id,
                    name,
                    cat,
                    listUrl,
                    listStatus + (listReason.isEmpty() ? "" : " (" + listReason + ")"),
                    String.valueOf(itemsFound),
                    detailRatio,
                    primaryIssue,
                    suggestedAction,
                    "",   // userDecision: keep / drop / fix-url:XXX / fix-parser / wait-server
                    ""    // userNote
            });
        }

        // 按优先级排序
        rows.sort(Comparator.comparingInt(a -> Integer.parseInt(a[0])));

        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF');  // UTF-8 BOM，让 Excel 直接识别中文
        csv.append("sourceId,name,category,listUrl,listStatus,itemsFound,detailOkRatio,primaryIssue,suggestedAction,userDecision,userNote\n");
        for (String[] row : rows) {
            for (int i = 1; i < row.length; i++) {
                if (i > 1) csv.append(',');
                csv.append(csvEscape(row[i]));
            }
            csv.append('\n');
        }
        try {
            Files.writeString(path, csv.toString());
        } catch (IOException e) {
            System.err.println("写入 url-audit.csv 失败: " + e.getMessage());
        }
    }

    private static String csvEscape(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /** 按名称关键字归类到图 2 的子分类（新闻/党建/合作/科研/学生/通知/规章/招生/招聘）。 */
    private static String classifyByName(String name) {
        if (name == null) return "OTHER";
        if (name.matches(".*(党建|党群|党的建设|党务|理论学习|廉洁|灯塔|青马|团日|团建|党政|统战|党团|思政).*")) return "党建";
        if (name.matches(".*(招聘|诚聘).*")) return "招聘";
        if (name.matches(".*(专升本|招生|奖助).*")) return "招生";
        if (name.matches(".*(合作|国际|校企|成果转化|境外|来访|院企|交流|社会服务|媒体聚焦).*")) return "合作交流";
        if (name.matches(".*(科研|学术|讲座|讲坛|学科建设|研究成果|科研资讯|测试服务|收费标准|科研成果|学术活动|学术讲座|讲座新闻|学术信息|学术交流|讲座通知|科学研究|科普|人工智能|大事记要).*")) return "科研学术";
        if (name.matches(".*(团学|学子|校园活动|校园生活|学生活动|社会实践|荣誉殿堂|学科竞赛|学生资助|聚焦服务|青年大学习|科技创新|团学风采|工会活动|文娱活动|体育活动|竞赛荣誉|教学动态|就业指导).*")) return "学生工作";
        if (name.matches(".*(规章|制度|政策法规|政策文件|政策规定|政策信息|服务指南|办事指南|培训通知|信息公开|人事制度|政策规章|审计公告|就业政策).*")) return "规章制度";
        if (name.matches(".*(通知|公告).*")) return "通知公告";
        if (name.matches(".*(新闻|动态|焦点|资讯|要闻|院务|风光).*")) return "新闻动态";
        return "其他";
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
            // 检测：detail 实际重定向到登录/系统提示页（content.jsp → auth.htm 等）
            // 这种文章对未登录用户不可见，不计入 NO_TITLE/CONTENT 统计
            String body = detailResp.body();
            String finalPath = detailResp.uri() != null ? detailResp.uri().getPath() : "";
            boolean isAuthRedirect = finalPath.contains("/auth.htm")
                    || finalPath.contains("/system/resource/code/auth")
                    || (body != null && body.length() < 2000
                        && (body.contains("您访问的页面未找到") || body.contains("<title>系统提示</title>")));
            if (isAuthRedirect) {
                return new ArticleReport(idx, safeTitle(item), detailUrl, "external",
                        List.of("跳转到登录/系统提示页"), null, 0);
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
            boolean hasAttach = content.getAttachments() != null && !content.getAttachments().isEmpty();
            // 有附件时放宽正文判定：许多"预算/决算/PDF 公示"页面是 iframe 嵌入 PDF，
            // 正文只有几十字是正常，附件本身就是内容主体。
            if (contentLen < 50 && !hasAttach) issues.add("缺正文(len=" + contentLen + ")");

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
        // 第一次失败或非 200 → 等 500ms 重试 1 次
        // 动机：并发 8 线程诊断时，服务端偶发返回 404/503（curl 单发同 URL 是 200）
        // 通过一次重试可消除抖动，区分"真 4xx"和"并发抖动"
        IOException lastEx = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 || attempt == 1) return resp;
            } catch (IOException e) {
                lastEx = e;
                if (attempt == 1) throw e;
            }
            try { Thread.sleep(500); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
        }
        if (lastEx != null) throw lastEx;
        // 理论上不会到这里
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
