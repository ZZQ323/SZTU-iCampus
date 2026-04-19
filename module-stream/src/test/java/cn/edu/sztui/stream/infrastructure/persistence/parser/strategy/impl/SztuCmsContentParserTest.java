package cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl;

import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.SourceConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ContentParserResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SztuGwtContentParser 详情页解析测试（覆盖博达 CMS 的 15+ 模板变体）。
 * <p>
 * 每个样本来自不同学校子域名的真实页面，验证 title + 正文选择器在各模板下都能命中。
 * 作者/发布时间是"Best-effort"，并非每个模板都提供，故单独按源名断言是否必须。
 */
class SztuCmsContentParserTest {

    private final SztuGwtContentParser parser = new SztuGwtContentParser();

    private String readSample(String filename) throws Exception {
        String path = "/parser-samples/content/" + filename;
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertNotNull(in, "测试样本不存在: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private SourceConfig mockConfig(String id, String baseUrl) {
        SourceConfig cfg = new SourceConfig();
        cfg.setId(id);
        cfg.setBaseUrl(baseUrl);
        cfg.setParserType("sztu-cms");
        return cfg;
    }

    /**
     * 按 (源 id, baseUrl, 样本文件, 期望作者非空, 期望发布时间非空) 提供断言。
     * 不是每个模板都有作者/时间，故逐源声明。
     */
    static Stream<Arguments> samples() {
        return Stream.of(
                //        id,            baseUrl,                                file,                    期望author, 期望time
                Arguments.of("sgim-xyxw", "https://sgim.sztu.edu.cn",             "content-sgim.html",     false, true),
                Arguments.of("kyb-tzgg",  "https://kyb.sztu.edu.cn",              "content-kyb.html",      true,  true),
                Arguments.of("jw-jdt",    "https://jw.sztu.edu.cn",               "content-jw.html",       true,  true),
                Arguments.of("ime-tzgg",  "https://ime.sztu.edu.cn",              "content-ime.html",      false, true),
                Arguments.of("www-wyhd",  "https://www.sztu.edu.cn",              "content-www-wyhd.html", true,  true),
                Arguments.of("www-xyxw",  "https://www.sztu.edu.cn",              "content-www-xyxw.html", true,  true),
                Arguments.of("icoc-xyxw", "https://icoc.sztu.edu.cn",             "content-icoc.html",     false, true),
                Arguments.of("jyzd-tzgg", "https://jyzd.sztu.edu.cn",             "content-jyzd.html",     false, true),
                Arguments.of("sao-tzgg",  "https://sao.sztu.edu.cn",              "content-sao.html",      true,  true),
                Arguments.of("intl-jdxw", "https://international.sztu.edu.cn",    "content-intl.html",     true,  true),
                Arguments.of("gra-xwdt",  "https://gra.sztu.edu.cn",              "content-gra.html",      false, true),
                Arguments.of("cmnf-tzgg", "https://cmnf.sztu.edu.cn",             "content-cmnf.html",     false, true),
                Arguments.of("zs-zsdt",   "https://zs.sztu.edu.cn",               "content-zs.html",       false, true),
                Arguments.of("cep-xwdt",  "https://cep.sztu.edu.cn",              "content-cep.html",      true,  true),
                Arguments.of("xtw-tzgg",  "https://xtw.sztu.edu.cn",              "content-xtw.html",      false, true),
                Arguments.of("future-xwzx","https://futuretechnologyschool.sztu.edu.cn","content-future.html",false,true),
                Arguments.of("nmne-xydt", "https://nmne.sztu.edu.cn",             "content-nmne.html",     true,  true),
                Arguments.of("sfl-xyxw",  "https://sfl.sztu.edu.cn",              "content-sfl.html",      true,  true),
                Arguments.of("music-ywgz","https://musicyyds.sztu.edu.cn",        "content-music.html",    true,  false),
                Arguments.of("ai-yxxw",   "https://ai.sztu.edu.cn",               "content-ai.html",       false, false)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("博达 CMS 详情解析跨模板兼容")
    void parseContent_coversTemplates(String id, String baseUrl, String file,
                                      boolean expectAuthor, boolean expectTime) throws Exception {
        String html = readSample(file);
        ContentParserResult result = parser.parseContent(html, mockConfig(id, baseUrl), "test-" + id);

        assertNotNull(result, id + ": 结果不为 null");
        assertTrue(result.isSuccess(), id + ": 解析应成功，错误: " + result.getErrorMessage());

        assertNotNull(result.getTitle(), id + ": 应提取出标题");
        assertFalse(result.getTitle().isBlank(), id + ": 标题不能空");

        assertNotNull(result.getContent(), id + ": 应提取出正文");
        assertTrue(result.getContent().length() > 50,
                id + ": 正文过短 (len=" + result.getContent().length() + ")");

        if (expectAuthor) {
            assertNotNull(result.getAuthor(), id + ": 该模板应含作者/来源");
            assertFalse(result.getAuthor().isBlank(), id + ": 作者不能空");
        }
        if (expectTime) {
            assertNotNull(result.getPublishTime(), id + ": 该模板应含发布时间");
            assertFalse(result.getPublishTime().isBlank(), id + ": 时间不能空");
        }
    }
}
