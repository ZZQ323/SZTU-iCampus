package cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl;

import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.SourceConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ContentParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 教务内网解析器离线单测。
 * <p>
 * 已覆盖：详情页（公告留言-详情）
 * 待覆盖：列表页（需要实际的 /ggly/ysgg_query 响应样本；目前只有父 shell）
 */
class AcdmInboxParserTest {

    private final AcdmInboxParser parser = new AcdmInboxParser();

    // ==================== 详情 ====================

    @Test
    void parseContent_real_sample_extracts_title() throws IOException {
        String html = loadSample("inbox-detail.htm");
        ContentParserResult result = parser.parseContent(html, mockSource(), "ABC123");

        assertTrue(result.isSuccess());
        assertEquals("ABC123", result.getId());
        // 样本标题是"关于创意设计学院学院部分课程停开的通知（2025.3.16）"
        assertTrue(result.getTitle().contains("课程停开"),
                "标题应含'课程停开'，实际：" + result.getTitle());
    }

    @Test
    void parseContent_extracts_publish_time() throws IOException {
        String html = loadSample("inbox-detail.htm");
        ContentParserResult result = parser.parseContent(html, mockSource(), "X");

        assertNotNull(result.getPublishTime());
        assertTrue(result.getPublishTime().contains("2026-03-16"),
                "发布时间应含 2026-03-16，实际：" + result.getPublishTime());
    }

    @Test
    void parseContent_extracts_author() throws IOException {
        String html = loadSample("inbox-detail.htm");
        ContentParserResult result = parser.parseContent(html, mockSource(), "X");

        assertNotNull(result.getAuthor());
        assertTrue(result.getAuthor().contains("彭凯风"),
                "发布人应含'彭凯风'，实际：" + result.getAuthor());
    }

    @Test
    void parseContent_extracts_non_empty_body() throws IOException {
        String html = loadSample("inbox-detail.htm");
        ContentParserResult result = parser.parseContent(html, mockSource(), "X");

        String content = result.getContent();
        assertNotNull(content);
        assertFalse(content.isBlank(), "正文不应为空");
        // 样本正文含"各位同学"、"课程截至到3月16日"之类特征词
        assertTrue(content.contains("各位同学") || content.contains("选课"),
                "正文特征词缺失");
    }

    @Test
    void parseContent_empty_html_fails_gracefully() {
        ContentParserResult result = parser.parseContent("", mockSource(), "X");
        assertFalse(result.isSuccess());
    }

    // ==================== 辅助方法 ====================

    @Test
    void fixWebVpnEncoding_restores_quotes() {
        String encoded = "<p style=&s380color:red&s380>hi</p>";
        String fixed = AcdmInboxParser.fixWebVpnEncoding(encoded);
        assertEquals("<p style=\"color:red\">hi</p>", fixed);
    }

    @Test
    void extractId_from_ggid_query() {
        assertEquals("202503001", AcdmInboxParser.extractId("/ggly/gglyShow.do?ggid=202503001", ""));
        assertEquals("202503001", AcdmInboxParser.extractId("/ggly/gglyShow.do?ggid=202503001&x=1", ""));
    }

    @Test
    void extractId_from_js_onclick() {
        assertEquals("abc123", AcdmInboxParser.extractId("", "showGg('abc123')"));
        assertEquals("abc123", AcdmInboxParser.extractId("", "readMsg(\"abc123\")"));
    }

    @Test
    void extractId_none_returns_empty() {
        assertEquals("", AcdmInboxParser.extractId("", ""));
        assertEquals("", AcdmInboxParser.extractId("/random/path.do", ""));
    }

    @Test
    void isDateLike_matches_common_patterns() {
        assertTrue(AcdmInboxParser.isDateLike("2026-03-16"));
        assertTrue(AcdmInboxParser.isDateLike("2026/03/16"));
        assertTrue(AcdmInboxParser.isDateLike("发布 2026-03-16 11:23"));
        assertFalse(AcdmInboxParser.isDateLike("hello"));
        assertFalse(AcdmInboxParser.isDateLike(""));
    }

    // ==================== 列表（样本缺失，保守测试） ====================

    @Test
    void parseList_empty_html_fails() {
        ListParserResult result = parser.parseList("", mockSource(), 1);
        assertFalse(result.isSuccess());
    }

    @Test
    void parseList_with_title_anchor_extracts_item() {
        // 手工模拟强智列表页的典型结构（.title 锚点 + ggid query）
        String html = "<html><body>" +
                "<table class='Nsb_r_list'>" +
                "  <tr>" +
                "    <td>1</td>" +
                "    <td><a class='title' href='/ggly/gglyShow.do?ggid=111' onclick='readgg(\"111\")'>关于春季开课的通知</a></td>" +
                "    <td>2026-03-16 11:23</td>" +
                "  </tr>" +
                "</table></body></html>";

        ListParserResult result = parser.parseList(html, mockSource(), 1);
        assertTrue(result.isSuccess());
        assertEquals(1, result.getItems().size());

        var item = result.getItems().get(0);
        assertEquals("111", item.getId());
        assertTrue(item.getTitle().contains("春季开课"));
        assertTrue(item.getPublishDate().contains("2026-03-16"));
    }

    // ==================== fixture ====================

    private SourceConfig mockSource() {
        SourceConfig s = new SourceConfig();
        s.setId("acdm-ysgg");
        s.setName("已收公告");
        s.setBaseUrl("https://jwxt.sztu.edu.cn/jsxsd");
        return s;
    }

    private String loadSample(String name) throws IOException {
        var stream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream("parser-samples/acdm/" + name),
                "sample not found: " + name);
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
}
