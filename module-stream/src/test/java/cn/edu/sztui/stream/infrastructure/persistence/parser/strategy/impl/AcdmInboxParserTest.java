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
    void parseList_login_page_flags_auth_expired() throws IOException {
        String html = loadSample("inbox-login-page.htm");
        ListParserResult result = parser.parseList(html, mockSource(), 1);

        assertFalse(result.isSuccess(), "登录页应判失败");
        assertTrue(result.isAuthExpired(), "登录页应置 authExpired=true");
        assertNull(result.getItems(), "登录页不应解出 items");
    }

    @Test
    void isJsxsdLoginPage_true_for_common_markers() {
        assertTrue(AcdmInboxParser.isJsxsdLoginPage(
                "<html><body><form action='/jsxsd/xk/LoginToXk'></form></body></html>"));
        assertTrue(AcdmInboxParser.isJsxsdLoginPage(
                "<input id=\"userAccount\" name=\"userAccount\"/>"));
        assertTrue(AcdmInboxParser.isJsxsdLoginPage(
                "<html><head><title>教务管理系统登录</title></head></html>"));
        assertTrue(AcdmInboxParser.isJsxsdLoginPage(
                "<html><body><a href='/jsxsd/loginHome'>去登录</a></body></html>"));
    }

    @Test
    void isJsxsdLoginPage_false_for_normal_list_page() {
        assertFalse(AcdmInboxParser.isJsxsdLoginPage(
                "<html><body><table><tr><td><a class='title'>关于开课</a></td></tr></table></body></html>"));
        assertFalse(AcdmInboxParser.isJsxsdLoginPage(""));
        assertFalse(AcdmInboxParser.isJsxsdLoginPage(null));
    }

    @Test
    void parseList_ysgg_real_sample_extracts_items() throws IOException {
        String html = loadSample("inbox-ysgg-list.htm");
        ListParserResult result = parser.parseList(html, mockSource(), 1);

        assertTrue(result.isSuccess());
        assertFalse(result.getItems().isEmpty(), "已收公告应解出条目");

        var first = result.getItems().get(0);
        // 样本第一条："关于创意设计学院学院部分课程停开的通知（2025.3.16）"
        assertTrue(first.getTitle().contains("课程停开"),
                "第一条标题应含'课程停开'，实际：" + first.getTitle());
        // ggid 来自 openWindow('/jsxsd/ggly/ggly_show?ggid=85C3BF0E...')
        assertEquals("85C3BF0E183440729B1270EB60CA2AAF", first.getId(),
                "第一条 id 应从 openWindow 的 ggid 参数提取");
        assertEquals("彭凯风", first.getAuthor());
        assertTrue(first.getPublishDate().contains("2026-03-16"));
        assertEquals("学生公告", first.getCategoryName());
    }

    @Test
    void parseList_ysgg_every_item_has_id() throws IOException {
        String html = loadSample("inbox-ysgg-list.htm");
        ListParserResult result = parser.parseList(html, mockSource(), 1);
        assertTrue(result.isSuccess());
        for (var item : result.getItems()) {
            assertNotNull(item.getId());
            assertFalse(item.getId().isEmpty(), "无 id 的条目应被丢弃而不是留下空 id");
            assertNotNull(item.getTitle());
            assertFalse(item.getTitle().isEmpty());
        }
    }

    @Test
    void parseList_xxtz_real_sample_extracts_items_with_synthesized_ids() throws IOException {
        String html = loadSample("inbox-xxtz-list.htm");
        ListParserResult result = parser.parseList(html, mockSource(), 1);

        assertTrue(result.isSuccess());
        assertFalse(result.getItems().isEmpty(), "消息通知应解出条目");

        var first = result.getItems().get(0);
        // 标题 = 业务名称：消息内容
        assertTrue(first.getTitle().startsWith("教师调课申请"),
                "xxtz 标题应含业务名称前缀，实际：" + first.getTitle());
        assertTrue(first.getTitle().contains("调课申请"));
        // 合成 id：前缀 + 16 hex chars
        assertTrue(first.getId().startsWith("xxtz-"), "xxtz id 应以 'xxtz-' 开头");
        assertTrue(first.getId().length() >= 10);
        assertTrue(first.getPublishDate().contains("2024"));
    }

    @Test
    void synthesizeXxtzId_is_stable() {
        // 同样输入 → 同样输出（支持去重）
        String a = AcdmInboxParser.synthesizeXxtzId("调课", "老师 A 调课已通过", "2024-12-25 10:53:19");
        String b = AcdmInboxParser.synthesizeXxtzId("调课", "老师 A 调课已通过", "2024-12-25 10:53:19");
        assertEquals(a, b);
        // 不同输入 → 不同输出
        String c = AcdmInboxParser.synthesizeXxtzId("调课", "老师 B 调课已通过", "2024-12-25 10:53:19");
        assertNotEquals(a, c);
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
