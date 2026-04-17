package cn.edu.sztui.stream.application.external.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ArticleUrlResolver 单元测试
 * <p>
 * 验证各种 href 格式的 URL 解析规则：
 * - 绝对 URL
 * - 协议相对 URL (//)
 * - 根路径 (/)
 * - 上级路径 (../)
 * - 裸相对路径
 * - 外链识别（mp.weixin、非 sztu 域名）
 */
class ArticleUrlResolverTest {

    private static final String BASE_URL = "https://hr.sztu.edu.cn";

    // ==================== resolve() 测试 ====================

    @Test
    void resolve_nullHref_returnsNull() {
        assertNull(ArticleUrlResolver.resolve(null, BASE_URL));
    }

    @Test
    void resolve_emptyHref_returnsNull() {
        assertNull(ArticleUrlResolver.resolve("", BASE_URL));
        assertNull(ArticleUrlResolver.resolve("  ", BASE_URL));
    }

    @ParameterizedTest
    @CsvSource({
        // href, baseUrl, expected
        "'https://hr.sztu.edu.cn/info/1020/100.htm', 'https://hr.sztu.edu.cn', 'https://hr.sztu.edu.cn/info/1020/100.htm'",
        "'http://www.sztu.edu.cn/news.htm', 'https://hr.sztu.edu.cn', 'http://www.sztu.edu.cn/news.htm'",
    })
    void resolve_absoluteUrl_sameDomain_returnsAsIs(String href, String baseUrl, String expected) {
        assertEquals(expected, ArticleUrlResolver.resolve(href, baseUrl));
    }

    @Test
    void resolve_rootPath_prependsBaseUrl() {
        assertEquals("https://hr.sztu.edu.cn/info/1020/100.htm",
                ArticleUrlResolver.resolve("/info/1020/100.htm", BASE_URL));
    }

    @Test
    void resolve_parentPath_stripsDotDotSlash() {
        assertEquals("https://hr.sztu.edu.cn/info/1020/100.htm",
                ArticleUrlResolver.resolve("../info/1020/100.htm", BASE_URL));
        assertEquals("https://hr.sztu.edu.cn/info/1020/100.htm",
                ArticleUrlResolver.resolve("../../info/1020/100.htm", BASE_URL));
    }

    @Test
    void resolve_protocolRelative_prependsHttps() {
        String result = ArticleUrlResolver.resolve("//mp.weixin.qq.com/s/abc", BASE_URL);
        // 微信外链应加 EXTERNAL: 前缀
        assertNotNull(result);
        assertTrue(result.startsWith(ArticleUrlResolver.EXTERNAL_PREFIX));
        assertTrue(result.contains("https://mp.weixin.qq.com/s/abc"));
    }

    @Test
    void resolve_bareRelative_prependsBaseUrlSlash() {
        assertEquals("https://hr.sztu.edu.cn/info/1020/100.htm",
                ArticleUrlResolver.resolve("info/1020/100.htm", BASE_URL));
    }

    // ==================== isExternalLink() 测试 ====================

    @Test
    void isExternalLink_mpWeixin_returnsTrue() {
        assertTrue(ArticleUrlResolver.isExternalLink("https://mp.weixin.qq.com/s/xxxxxx"));
    }

    @Test
    void isExternalLink_bysjy_returnsTrue() {
        assertTrue(ArticleUrlResolver.isExternalLink("https://sztu.bysjy.com.cn/module/careers"));
    }

    @Test
    void isExternalLink_sztuDomain_returnsFalse() {
        assertFalse(ArticleUrlResolver.isExternalLink("https://hr.sztu.edu.cn/info/1020/100.htm"));
        assertFalse(ArticleUrlResolver.isExternalLink("https://www.sztu.edu.cn/jdjd/xyxw.htm"));
        assertFalse(ArticleUrlResolver.isExternalLink("https://jwxt-sztu-edu-cn-s.webvpn.sztu.edu.cn/"));
    }

    @Test
    void isExternalLink_otherDomain_returnsTrue() {
        assertTrue(ArticleUrlResolver.isExternalLink("https://www.gov.cn/news"));
        assertTrue(ArticleUrlResolver.isExternalLink("https://news.xinhuanet.com/"));
    }

    @Test
    void isExternalLink_null_returnsFalse() {
        assertFalse(ArticleUrlResolver.isExternalLink(null));
    }

    // ==================== extractId() 测试 ====================

    @Test
    void extractId_standardInfoUrl_returnsId() {
        assertEquals("100", ArticleUrlResolver.extractId("https://hr.sztu.edu.cn/info/1020/100.htm"));
        assertEquals("51057", ArticleUrlResolver.extractId("https://hr.sztu.edu.cn/info/1020/51057.htm"));
    }

    @Test
    void extractId_externalPrefix_returnsNull() {
        assertNull(ArticleUrlResolver.extractId(
                ArticleUrlResolver.EXTERNAL_PREFIX + "https://mp.weixin.qq.com/s/abc"));
    }

    @Test
    void extractId_null_returnsNull() {
        assertNull(ArticleUrlResolver.extractId(null));
    }

    // ==================== extractCategory() 测试 ====================

    @Test
    void extractCategory_standardInfoUrl_returnsCategory() {
        assertEquals("1020", ArticleUrlResolver.extractCategory("https://hr.sztu.edu.cn/info/1020/100.htm"));
        assertEquals("1043", ArticleUrlResolver.extractCategory("https://hr.sztu.edu.cn/info/1043/4201.htm"));
    }

    @Test
    void extractCategory_relativeInfoPath_returnsCategory() {
        assertEquals("1020", ArticleUrlResolver.extractCategory("info/1020/100.htm"));
    }

    @Test
    void extractCategory_externalPrefix_returnsNull() {
        assertNull(ArticleUrlResolver.extractCategory(
                ArticleUrlResolver.EXTERNAL_PREFIX + "https://mp.weixin.qq.com/s/abc"));
    }

    @Test
    void extractCategory_nonInfoUrl_returnsNull() {
        assertNull(ArticleUrlResolver.extractCategory("https://hr.sztu.edu.cn/tzgg.htm"));
    }
}
