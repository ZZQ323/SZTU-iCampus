package cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl;

import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.SourceConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ContentParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ContentParserResult.AttachmentInfo;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ParserStrategy;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 深圳技术大学公文通 - 详情解析器
 * <p>
 * 实际 HTML 结构：
 *   标题：h1.article-title
 *   元信息：div.article-sm
 *   正文：#vsb_content .v_news_content
 *   附件：ul.fujian li a
 *   导航：div.article-link p
 * <p>
 * ⭐ 改动：builder 用 .content() 而非 .htmlContent()，与 ContentParserResult 字段名一致
 */
@Slf4j
@Component
public class SztuGwtContentParser implements ParserStrategy {

    public static final String TYPE = "sztu-gwt";

    private static final Pattern PUBLISH_TIME_PATTERN =
            Pattern.compile("发布时间[：:]\\s*(\\d{4}年\\d{1,2}月\\d{1,2}日\\s*\\d{1,2}:\\d{2})");
    private static final Pattern ID_PATTERN = Pattern.compile("(\\d+)\\.htm");

    @Override
    public String getType() { return TYPE; }

    @Override
    public ListParserResult parseList(String html, SourceConfig sourceConfig, int page) {
        return ListParserResult.fail("请使用 SztuGwtListParser 解析列表");
    }

    @Override
    public ContentParserResult parseContent(String html, SourceConfig sourceConfig, String itemId) {
        if (!StringUtils.hasText(html)) {
            return ContentParserResult.fail("HTML 内容为空");
        }

        try {
            Document doc = Jsoup.parse(html);
            String baseUrl = sourceConfig != null ? sourceConfig.getBaseUrl() : "";

            if (isErrorPage(doc)) {
                return ContentParserResult.fail("访问被拒绝或需要登录");
            }

            String title = parseTitle(doc);
            String author = parseAuthor(doc);
            String publishTime = parsePublishTime(doc);
            String htmlContent = parseHtmlContent(doc, baseUrl);
            String plainText = htmlContent != null ? Jsoup.parse(htmlContent).text() : "";
            List<AttachmentInfo> attachments = parseAttachments(doc, baseUrl);
            String[] prevInfo = parsePrevArticle(doc);
            String[] nextInfo = parseNextArticle(doc);

            ContentParserResult result = ContentParserResult.builder()
                    .success(true)
                    .id(itemId)
                    .title(title)
                    .author(author)
                    .publishTime(publishTime)
                    .content(htmlContent)           // ⭐ 改：.htmlContent() → .content()
                    .plainText(plainText.length() > 500 ? plainText.substring(0, 500) + "..." : plainText)
                    .attachments(attachments)
                    .prevId(prevInfo[0])
                    .prevTitle(prevInfo[1])
                    .nextId(nextInfo[0])
                    .nextTitle(nextInfo[1])
                    .build();

            log.debug("解析详情成功 - ID: {}, 标题: {}, 附件: {}", itemId, title, attachments.size());
            return result;

        } catch (Exception e) {
            log.error("解析详情失败 - ID: {}", itemId, e);
            return ContentParserResult.fail("解析失败: " + e.getMessage());
        }
    }

    // ==================== 选择器（与实际 HTML 对齐） ====================

    private String parseTitle(Document doc) {
        Element el = doc.selectFirst("h1.article-title");
        if (el == null) el = doc.selectFirst("h1.arti-title, h1.title, .news-title h1");
        if (el != null) return el.text().trim();
        String t = doc.title();
        if (StringUtils.hasText(t)) {
            int i = t.lastIndexOf("-");
            return i > 0 ? t.substring(0, i).trim() : t.trim();
        }
        return null;
    }

    private String parseAuthor(Document doc) {
        Element metaElem = doc.selectFirst("div.article-sm, .article-sm");
        if (metaElem != null) {
            String text = metaElem.text();
            int s = text.indexOf("作者：");
            if (s == -1) s = text.indexOf("作者:");
            if (s != -1) {
                int start = s + 3, end = start;
                while (end < text.length()) {
                    char c = text.charAt(end);
                    if (c == ' ' || c == '\u00a0' || c == '\t' || c == '发') break;
                    end++;
                }
                if (end > start) return text.substring(start, end).trim();
            }
        }
        Element el = doc.selectFirst(".arti-metas span:contains(来源), .article-source, .author");
        if (el != null) return el.text().replaceFirst("^(来源|作者|发布单位)[：:]\\s*", "").trim();
        return null;
    }

    private String parsePublishTime(Document doc) {
        Element metaElem = doc.selectFirst("div.article-sm, .article-sm");
        if (metaElem != null) {
            Matcher m = PUBLISH_TIME_PATTERN.matcher(metaElem.text());
            if (m.find()) return m.group(1);
        }
        Element el = doc.selectFirst(".arti-metas span:contains(时间), .article-time, time");
        if (el != null) {
            Matcher m = Pattern.compile("(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}(\\s+\\d{1,2}:\\d{2}(:\\d{2})?)?)").matcher(el.text());
            if (m.find()) return m.group(1).replace("/", "-");
        }
        return null;
    }

    private String parseHtmlContent(Document doc, String baseUrl) {
        Element el = doc.selectFirst("#vsb_content .v_news_content");
        if (el == null) el = doc.selectFirst(".v_news_content");
        if (el == null) el = doc.selectFirst("#vsb_content");
        if (el == null) {
            for (String sel : new String[]{".wp_articlecontent", ".article-content", ".content", "#content"}) {
                el = doc.selectFirst(sel);
                if (el != null && StringUtils.hasText(el.text())) break;
            }
        }
        return el != null ? cleanHtml(el, baseUrl) : "";
    }

    private List<AttachmentInfo> parseAttachments(Document doc, String baseUrl) {
        List<AttachmentInfo> list = new ArrayList<>();
        Elements elems = doc.select("ul.fujian li a, UL.fujian li a");
        if (elems.isEmpty()) {
            elems = doc.select(".attachment a, a[href$=.pdf], a[href$=.doc], a[href$=.docx], a[href$=.xls], a[href$=.xlsx], a[href$=.zip]");
        }
        for (Element a : elems) {
            String name = a.text().trim(), url = a.attr("href");
            if (!StringUtils.hasText(name) || !StringUtils.hasText(url)) continue;
            if (!url.startsWith("http")) url = (url.startsWith("/") ? baseUrl : baseUrl + "/") + url;
            list.add(AttachmentInfo.builder().name(name).url(url).type(inferType(url, name)).build());
        }
        return list;
    }

    private String[] parsePrevArticle(Document doc) {
        // 多种选择器覆盖不同 CMS 模板
        Element nav = doc.selectFirst("div.article-link, .article-link, .page-turning, .pre-next, .article-nav");
        if (nav != null) {
            for (Element el : nav.select("p, div, span, li")) {
                String text = el.text();
                if (text.contains("上一篇") || text.contains("上一条") || text.contains("前一篇")) {
                    Element a = el.selectFirst("a");
                    if (a != null) return new String[]{extractId(a.attr("href")), a.text().trim()};
                }
            }
        }
        return new String[]{null, null};
    }

    private String[] parseNextArticle(Document doc) {
        Element nav = doc.selectFirst("div.article-link, .article-link, .page-turning, .pre-next, .article-nav");
        if (nav != null) {
            for (Element el : nav.select("p, div, span, li")) {
                String text = el.text();
                if (text.contains("下一篇") || text.contains("下一条") || text.contains("后一篇")) {
                    Element a = el.selectFirst("a");
                    if (a != null) return new String[]{extractId(a.attr("href")), a.text().trim()};
                }
            }
        }
        return new String[]{null, null};
    }

    // ==================== 工具 ====================

    private String cleanHtml(Element el, String baseUrl) {
        Element c = el.clone();
        c.select("script, style, iframe").remove();

        // 处理图片：修复 URL + 注入响应式内联样式
        for (Element img : c.select("img")) {
            String s = img.attr("src");
            if (!s.startsWith("http") && !s.startsWith("data:")) {
                img.attr("src", s.startsWith("/") ? baseUrl + s : baseUrl + "/" + s.replaceAll("^\\.\\./+", ""));
            }
            // ⭐ 移除固定尺寸属性，注入响应式内联样式（微信小程序 rich-text 不支持 scoped CSS）
            img.removeAttr("width");
            img.removeAttr("height");
            String existingStyle = img.attr("style");
            String responsiveStyle = "max-width:100%;height:auto;display:block;";
            img.attr("style", StringUtils.hasText(existingStyle)
                    ? existingStyle + ";" + responsiveStyle : responsiveStyle);
        }

        // 处理链接：修复相对 URL
        for (Element a : c.select("a")) {
            String h = a.attr("href");
            if (!h.startsWith("http") && !h.startsWith("#") && !h.startsWith("javascript:")) {
                a.attr("href", h.startsWith("/") ? baseUrl + h : baseUrl + "/" + h);
            }
        }

        return c.html();
    }

    private String extractId(String url) { if (url == null) return null; Matcher m = ID_PATTERN.matcher(url); return m.find() ? m.group(1) : null; }
    private String inferType(String url, String name) { String l = (url + " " + name).toLowerCase(); if (l.contains(".pdf")) return "pdf"; if (l.contains(".doc")) return "word"; if (l.contains(".xls")) return "excel"; if (l.contains(".ppt")) return "ppt"; if (l.contains(".zip") || l.contains(".rar")) return "archive"; return "file"; }
    private boolean isErrorPage(Document doc) { String t = doc.title().toLowerCase(); String b = doc.body() != null ? doc.body().text().toLowerCase() : ""; return t.contains("error") || t.contains("错误") || t.contains("404") || t.contains("登录") || b.contains("请登录"); }
}