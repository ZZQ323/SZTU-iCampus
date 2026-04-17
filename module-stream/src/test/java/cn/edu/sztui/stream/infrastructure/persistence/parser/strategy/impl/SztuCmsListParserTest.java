package cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl;

import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.SourceConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult.InfoItemMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SztuCmsListParser 单元测试
 * <p>
 * 使用 src/test/resources/parser-samples/ 中的真实 HTML 样本进行离线测试。
 * 每个样本覆盖一种页面变体（B/C/D/E/F/G/H 型）。
 */
class SztuCmsListParserTest {

    private final SztuCmsListParser parser = new SztuCmsListParser();

    /** 从 classpath 读取 HTML 样本（使用 ASCII 文件名，避免中文路径编码问题） */
    private String readSample(String filename) throws Exception {
        String path = "/parser-samples/" + filename;
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("测试样本不存在: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private SourceConfig mockConfig(String id, String baseUrl, String categoryCode) {
        SourceConfig cfg = new SourceConfig();
        cfg.setId(id);
        cfg.setBaseUrl(baseUrl);
        cfg.setCategoryCode(categoryCode);
        cfg.setParserType("sztu-cms");
        return cfg;
    }

    private void assertHasValidItems(ListParserResult result) {
        assertNotNull(result, "result 不为 null");
        assertTrue(result.isSuccess(), "解析应成功");
        assertNotNull(result.getItems(), "items 不为 null");
        assertFalse(result.getItems().isEmpty(), "items 不为空");
        for (InfoItemMeta item : result.getItems()) {
            assertNotNull(item.getId(), "每条 item 必有 id");
            assertNotNull(item.getTitle(), "每条 item 必有 title");
            assertFalse(item.getTitle().isBlank(), "title 不能空");
        }
    }

    // ==================== B 型：卡片式 ====================

    @Test
    @DisplayName("B型-卡片式：文娱活动")
    void parseList_type_B_wenyuhuodong() throws Exception {
        String html = readSample("www-wyhd.html");
        ListParserResult result = parser.parseList(
                html, mockConfig("www-wyhd", "https://www.sztu.edu.cn", "1040"), 1);
        assertHasValidItems(result);
    }

    // ==================== C 型：图文混排 ====================

    @Test
    @DisplayName("C型-图文混排：体育活动")
    void parseList_type_C_tiyuhuodong() throws Exception {
        String html = readSample("www-tyhd.html");
        ListParserResult result = parser.parseList(
                html, mockConfig("www-tyhd", "https://www.sztu.edu.cn", null), 1);
        assertTrue(result.isSuccess());
        assertNotNull(result.getItems());
        assertFalse(result.getItems().isEmpty(), "体育活动页应有 items");
    }

    // ==================== D 型：人力资源部 ====================

    @Test
    @DisplayName("D型-无图有序号：人力资源部-通知公告")
    void parseList_type_D_hr() throws Exception {
        String html = readSample("hr-tzgg.html");
        ListParserResult result = parser.parseList(
                html, mockConfig("hr-tzgg", "https://hr.sztu.edu.cn", "1020"), 1);
        assertHasValidItems(result);
    }

    // ==================== H 型：教务部 ====================

    @Test
    @DisplayName("H型-教务部教学动态")
    void parseList_type_H_jw_jdt() throws Exception {
        String html = readSample("jw-jdt.html");
        ListParserResult result = parser.parseList(
                html, mockConfig("jw-jdt", "https://jw.sztu.edu.cn", "1005"), 1);
        assertHasValidItems(result);
    }

    // ==================== 校园新闻 ====================

    @Test
    @DisplayName("学校新闻：校园新闻")
    void parseList_www_xyxw() throws Exception {
        String html = readSample("www-xyxw.html");
        ListParserResult result = parser.parseList(
                html, mockConfig("www-xyxw", "https://www.sztu.edu.cn", "1003"), 1);
        assertHasValidItems(result);
    }

    // ==================== 边界场景 ====================

    @Test
    @DisplayName("边界：空 HTML")
    void parseList_emptyHtml_returnsEmpty() {
        ListParserResult result = parser.parseList("", mockConfig("x", "https://x.com", null), 1);
        assertNotNull(result);
        assertTrue(result.getItems() == null || result.getItems().isEmpty());
    }

    @Test
    @DisplayName("边界：null HTML 不抛异常")
    void parseList_nullHtml_noException() {
        assertDoesNotThrow(() -> {
            parser.parseList(null, mockConfig("x", "https://x.com", null), 1);
        });
    }
}
