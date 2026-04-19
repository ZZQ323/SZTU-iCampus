package cn.edu.sztui.stream.healthcheck;

import cn.edu.sztui.stream.application.external.engine.ArticleUrlResolver;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.SourceConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfigLoader;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ContentParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult.InfoItemMeta;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl.SztuCmsContentParser;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl.SztuCmsListParser;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl.SztuGwtContentParser;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl.SztuGwtListParser;
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
import java.util.*;

/**
 * 全量在线爬虫诊断工具
 * <p>
 * 不需要 Spring 上下文和 Redis，直接用 JDK HttpClient + 手动实例化解析器。
 * <p>
 * 对每个公开源：爬取列表页 → 解析 items → 取前 N 篇 → 爬取详情页 → 检查字段完整性。
 * <p>
 * 输出诊断报告到控制台 + 写入文件 diagnostic-report.txt。
 * <p>
 * 运行：
 * <pre>
 * ./gradlew :module-stream:test --tests "OnlineCrawlDiagnostic" --console=plain --info
 * </pre>
 */
@Tag("online")
class OnlineCrawlDiagnostic {

    /** 每个源最多检查的文章数 */
    private static final int MAX_DETAIL_CHECK = 3;

    /** HTTP 超时 */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:148.0) Gecko/20100101 Firefox/148.0";

    // 手动实例化解析器（不需要 Spring）
    private final SztuCmsListParser cmsListParser = new SztuCmsListParser();
    private final SztuGwtListParser gwtListParser = new SztuGwtListParser();
    private final SztuGwtContentParser gwtContentParser = new SztuGwtContentParser();

    // 加载配置（不需要 Spring，CrawlerConfigLoader 自己读 YAML）
    private final CrawlerConfigLoader configLoader = new CrawlerConfigLoader();

    @Test
    void runFullDiagnostic() throws Exception {
        // 手动初始化配置加载器
        configLoader.init();

        // 创建忽略 SSL 证书的 HttpClient
        HttpClient httpClient = createInsecureHttpClient();

        List<SourceConfig> publicSources = configLoader.getEnabledSources().stream()
                .filter(s -> !s.isRequiresAuth())
                .toList();

        StringBuilder report = new StringBuilder();
        report.append("=== 全量在线诊断报告 ===\n");
        report.append("诊断时间: ").append(LocalDateTime.now()).append("\n");
        report.append("公开源数量: ").append(publicSources.size()).append("\n\n");

        // 统计计数器
        int totalSources = 0, okSources = 0, failSources = 0, emptySources = 0;
        int totalArticles = 0, okArticles = 0;
        int missingTitle = 0, missingAuthor = 0, missingContent = 0, detail404 = 0, detailError = 0;

        for (SourceConfig source : publicSources) {
            totalSources++;
            report.append("──── ").append(source.getName())
                    .append(" (").append(source.getId())
                    .append(", parser=").append(source.getParserType())
                    .append(") ────\n");

            try {
                // 1. 构建列表页 URL
                String listUrl = buildListUrl(source);
                if (listUrl == null) {
                    report.append("  ❌ 无列表URL配置\n\n");
                    failSources++;
                    continue;
                }

                // 2. 请求列表页
                HttpResponse<String> listResp = httpGet(httpClient, listUrl);
                if (listResp.statusCode() != 200) {
                    report.append("  ❌ 列表页 HTTP ")
                            .append(listResp.statusCode()).append(": ").append(listUrl).append("\n\n");
                    failSources++;
                    continue;
                }

                // 3. 解析列表
                ListParserResult listResult = parseList(source, listResp.body());
                if (listResult == null || listResult.getItems() == null || listResult.getItems().isEmpty()) {
                    report.append("  ⚠️  列表解析无结果: ").append(listUrl).append("\n\n");
                    emptySources++;
                    continue;
                }

                int itemCount = listResult.getItems().size();
                report.append("  列表页 OK: ").append(itemCount).append(" 条")
                        .append("  URL: ").append(listUrl).append("\n");
                okSources++;

                // 4. 检查前 N 篇文章详情
                int checkCount = Math.min(MAX_DETAIL_CHECK, itemCount);
                for (int i = 0; i < checkCount; i++) {
                    InfoItemMeta item = listResult.getItems().get(i);
                    totalArticles++;

                    String detailUrl = resolveDetailUrl(source, item);
                    if (detailUrl == null
                            || detailUrl.startsWith("EXTERNAL:")
                            || ArticleUrlResolver.isExternalLink(detailUrl)) {
                        report.append("  [").append(i + 1).append("] ⏭️  外链/无URL: ")
                                .append(item.getTitle())
                                .append(detailUrl != null ? " | " + detailUrl : "")
                                .append("\n");
                        okArticles++; // 外链由前端"打开浏览器"跳转，不爬取
                        continue;
                    }

                    try {
                        HttpResponse<String> detailResp = httpGet(httpClient, detailUrl);
                        if (detailResp.statusCode() != 200) {
                            report.append("  [").append(i + 1).append("] ❌ HTTP ")
                                    .append(detailResp.statusCode())
                                    .append(": ").append(detailUrl).append("\n");
                            detail404++;
                            continue;
                        }

                        // 解析详情
                        ContentParserResult content = parseContent(source, detailResp.body(), item.getId());

                        List<String> issues = new ArrayList<>();
                        if (content == null || !content.isSuccess()) {
                            issues.add("解析失败" + (content != null ? ": " + content.getErrorMessage() : ""));
                        } else {
                            if (!StringUtils.hasText(content.getTitle())) {
                                issues.add("缺标题");
                                missingTitle++;
                            }
                            if (!StringUtils.hasText(content.getAuthor())) {
                                issues.add("缺来源");
                                missingAuthor++;
                            }
                            String htmlContent = content.getContent();
                            if (!StringUtils.hasText(htmlContent) || htmlContent.length() < 50) {
                                issues.add("缺正文(len=" + (htmlContent != null ? htmlContent.length() : 0) + ")");
                                missingContent++;
                            }
                        }

                        if (issues.isEmpty()) {
                            report.append("  [").append(i + 1).append("] ✅ ").append(item.getTitle()).append("\n");
                            okArticles++;
                        } else {
                            report.append("  [").append(i + 1).append("] ⚠️  ")
                                    .append(String.join(", ", issues))
                                    .append(" | ").append(safeTitle(item))
                                    .append(" | ").append(detailUrl).append("\n");
                        }
                    } catch (Exception e) {
                        report.append("  [").append(i + 1).append("] ❌ 请求异常: ")
                                .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage())
                                .append(" | ").append(detailUrl).append("\n");
                        detailError++;
                    }
                }
            } catch (Exception e) {
                report.append("  ❌ 源级异常: ")
                        .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
                failSources++;
            }
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
        report.append("  总计:           ").append(totalSources).append("\n\n");
        report.append("文章统计:\n");
        report.append("  完整:     ").append(okArticles).append("/").append(totalArticles).append("\n");
        report.append("  缺标题:   ").append(missingTitle).append("\n");
        report.append("  缺来源:   ").append(missingAuthor).append("\n");
        report.append("  缺正文:   ").append(missingContent).append("\n");
        report.append("  详情404:  ").append(detail404).append("\n");
        report.append("  请求异常: ").append(detailError).append("\n");

        // 输出到控制台
        System.out.println(report);

        // 写入文件
        Path reportPath = Path.of("diagnostic-report.txt");
        Files.writeString(reportPath, report.toString());
        System.out.println("\n报告已写入: " + reportPath.toAbsolutePath());
    }

    // ==================== 辅助方法 ====================

    private String buildListUrl(SourceConfig source) {
        if (StringUtils.hasText(source.getListUrl())) {
            return source.getListUrl();
        }
        if (StringUtils.hasText(source.getListUrlTemplate())) {
            return source.getListUrlTemplate()
                    .replace("{page}", "1")
                    .replace("{pageNum}", "1");
        }
        return null;
    }

    private String resolveDetailUrl(SourceConfig source, InfoItemMeta item) {
        // 优先使用 item 的 url（列表解析时提取的原始 URL）
        String itemUrl = item.getUrl();
        if (StringUtils.hasText(itemUrl) && itemUrl.startsWith("http")) {
            return itemUrl;
        }

        // Fallback: 用 detailUrlTemplate 构建
        String template = source.getDetailUrlTemplate();
        if (template != null && item.getId() != null) {
            String category = item.getCategoryCode();
            if (category == null) category = source.getCategoryCode();
            if (category == null) category = "";
            return template.replace("{id}", item.getId()).replace("{category}", category);
        }

        // 最后尝试拼接
        if (StringUtils.hasText(itemUrl)) {
            return source.getBaseUrl() + "/" + itemUrl;
        }
        return null;
    }

    private ListParserResult parseList(SourceConfig source, String html) {
        if ("sztu-gwt".equals(source.getParserType())) {
            return gwtListParser.parseList(html, source, 1);
        }
        return cmsListParser.parseList(html, source, 1);
    }

    private ContentParserResult parseContent(SourceConfig source, String html, String itemId) {
        // CMS 和 GWT 的详情解析器都委托给 SztuGwtContentParser
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

    /** 创建忽略 SSL 证书的 HttpClient（学校有些站点证书有问题） */
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
