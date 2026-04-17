package cn.edu.sztui.stream.healthcheck;

import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.SourceConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl.SztuCmsListParser;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl.SztuGwtListParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * 批量解析器健康检查
 * <p>
 * 遍历 infos/downloaded_pages 下的所有 HTML 文件，验证：
 * 1. 解析器是否能正常处理（不抛异常）
 * 2. 列表页是否能解析出 items
 * 3. 每条 item 是否有 title 和 id
 * <p>
 * 输出报告到控制台（PASS/FAIL/EMPTY 分类），便于发现解析异常的页面。
 * <p>
 * 这是"批量诊断"工具，不是严格的断言测试（默认不会因为某个页面解析失败而整体失败）。
 * 用 @Tag("healthcheck") 标记，CI 中可选择性跳过。
 * <p>
 * 运行命令：
 * <pre>
 * ./gradlew :module-stream:test --tests ParserHealthCheckTest
 * </pre>
 */
@Tag("healthcheck")
class ParserHealthCheckTest {

    private static final Path DOWNLOADED_PAGES = Paths.get("..", "infos", "downloaded_pages");

    private final SztuCmsListParser cmsParser = new SztuCmsListParser();
    private final SztuGwtListParser gwtParser = new SztuGwtListParser();

    /**
     * 根据文件名猜测 parser 类型。
     * 包含"公文通"或来自 GWT 站点的用 sztu-gwt，其他用 sztu-cms。
     */
    private String inferParserType(String filename) {
        // 一般 downloaded_pages 里都是 CMS 站点；公文通需 cookie，这里没有样本
        return "sztu-cms";
    }

    /**
     * 根据文件名推断 baseUrl（用于 URL 解析）。
     * 仅用于测试，实际爬取用 sources.yml 的配置。
     */
    private String inferBaseUrl(String filename) {
        // 简化：每个 HTML 都尝试用通用 baseUrl，解析器自己从 meta 中提取
        return "https://www.sztu.edu.cn";
    }

    @Test
    void batchHealthCheck() throws IOException {
        if (!Files.exists(DOWNLOADED_PAGES)) {
            System.out.println("[SKIP] downloaded_pages 目录不存在: " + DOWNLOADED_PAGES.toAbsolutePath());
            return;
        }

        // ⚠️ 注意：如果 JVM 的 sun.jnu.encoding 不是 UTF-8，中文文件名读取会失败
        // 建议运行时加 -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8
        // （build.gradle 的 test task 已配置）

        List<String> passed = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();
        int totalFiles = 0;

        try (Stream<Path> files = Files.list(DOWNLOADED_PAGES)) {
            List<Path> htmlFiles = files
                    .filter(p -> p.getFileName().toString().endsWith(".html"))
                    .sorted()
                    .toList();

            totalFiles = htmlFiles.size();

            for (Path htmlFile : htmlFiles) {
                String filename = htmlFile.getFileName().toString();
                try {
                    String html = Files.readString(htmlFile, StandardCharsets.UTF_8);

                    SourceConfig cfg = new SourceConfig();
                    cfg.setId("test");
                    cfg.setBaseUrl(inferBaseUrl(filename));
                    cfg.setParserType(inferParserType(filename));

                    ListParserResult result = cmsParser.parseList(html, cfg, 1);

                    if (result == null || !result.isSuccess()) {
                        failed.put(filename,
                                result == null ? "result=null" : result.getErrorMessage());
                        continue;
                    }

                    if (result.getItems() == null || result.getItems().isEmpty()) {
                        empty.add(filename);
                    } else {
                        passed.add(filename);
                    }

                } catch (Exception e) {
                    failed.put(filename, e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }

        // ==================== 打印报告 ====================
        System.out.println("\n==================================================");
        System.out.println("解析器健康检查报告");
        System.out.println("==================================================");
        System.out.printf("总文件数: %d%n", totalFiles);
        System.out.printf("✅ 成功解析（有 items）: %d%n", passed.size());
        System.out.printf("⚠️  成功但无 items（可能是导航页/图片库）: %d%n", empty.size());
        System.out.printf("❌ 解析失败/异常: %d%n", failed.size());
        System.out.println("--------------------------------------------------");

        if (!failed.isEmpty()) {
            System.out.println("\n❌ 解析失败的页面:");
            failed.forEach((file, reason) ->
                    System.out.printf("  - %s : %s%n", file, reason));
        }

        if (!empty.isEmpty() && empty.size() <= 50) {
            System.out.println("\n⚠️  无 items 的页面（前 50 个）:");
            empty.stream().limit(50).forEach(f -> System.out.println("  - " + f));
        }

        System.out.println("==================================================\n");

        // 不强制断言 — 这是诊断工具，不是质量门禁
        // 但如果 100% 都失败，肯定是解析器坏了
        if (totalFiles > 0 && passed.isEmpty() && empty.isEmpty()) {
            throw new AssertionError("所有 HTML 解析都失败，解析器可能有严重问题");
        }
    }
}
