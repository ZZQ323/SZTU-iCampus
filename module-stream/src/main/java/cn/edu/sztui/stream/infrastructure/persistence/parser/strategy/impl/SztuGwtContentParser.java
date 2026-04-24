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
 * 深圳技术大学博达 CMS - 详情解析器（公文通 + 各子域名通用）。
 * <p>
 * 博达 CMS 在不同子域名有 15+ 套模板变体，故本类采用"有序选择器回退链"：
 * 对每个字段按模板变体列出一串 CSS 选择器，首个命中者为准。
 * 各模板变体请见 SztuCmsContentParserTest 中的样例。
 */
@Slf4j
@Component
public class SztuGwtContentParser implements ParserStrategy {

    public static final String TYPE = "sztu-gwt";

    private static final Pattern ID_PATTERN = Pattern.compile("(\\d+)\\.htm");

    /** 日期匹配：yyyy-MM-dd / yyyy/MM/dd / yyyy年MM月dd日，可带时间 */
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}[日]?(\\s*\\d{1,2}[:：]\\d{2}(:\\d{2})?)?)");

    /** 标题选择器有序回退链（覆盖所有 SZTU 博达 CMS 子域名模板） */
    private static final List<String> TITLE_SELECTORS = List.of(
            "h1.article-title",
            "h1.arti-title",
            ".news-title h1",
            "div.detail-title h4",                      // kyb / jw
            "div.content > div.title",                  // sgim
            "div.tybt",                                  // ime
            "div.c-tit h3",                              // www.sztu
            "div.content_t h3",                          // icoc
            "div.detail-content h2.ft-18",               // jyzd
            "div.detail-content h2",
            "div.news_conent_two_title",                 // sao / intl 等变体（不含 nmne）
            "div.news_conent div[align=center] span[style*=font-weight]",  // nmne：无 class 仅内联样式
            "div.news_conent div[align=center] span",                       // nmne fallback：任意 span
            "section.n_detail div.ar_title h3",          // gra
            "div.ar_title h3",
            "#article-content h1",                       // cmnf
            "div.con_title h2.title",                    // zs / cep
            "div.con_title h2",
            "div.article_section div.article_title",     // xtw
            "div.contentBox h2.title",                   // future
            "div.article_box h2",                        // music
            "div.cnt_tit h2",                            // sfl
            "h1.title"
    );

    /** 元信息容器（含作者/来源/发布时间文本块） */
    private static final List<String> META_CONTAINERS = List.of(
            "div.article-sm", ".article-sm",
            "div.detail-title h6",
            "div.parameter",
            "div.newshow_ctrl_zi",
            "div.newshow_timer",
            "div.c-ifo",
            "div.content_t p",
            "div.content_t",
            "div.sub-row",
            "div.news_conent_two_js",
            "div.ar_title h6",
            "#article-content div.flex",
            "p.info",
            "div.details_msg",
            "div.left div.info",
            "div.sub_box",
            "div.cnt_note",
            ".arti-metas"
    );

    /** 作者/来源标签 */
    private static final String[] AUTHOR_LABELS =
            {"作者", "发布人", "来源", "信息来源", "发文单位", "发布单位"};

    /** 发布时间标签 */
    private static final String[] TIME_LABELS =
            {"发布时间", "日期", "时间"};

    @Override
    public String getType() { return TYPE; }

    @Override
    public ListParserResult parseList(String html, SourceConfig sourceConfig, int page) {
        return ListParserResult.fail("请使用 SztuGwtListParser 解析列表");
    }

    /** 模板指纹：每个字段在回退链里命中的第一个选择器（null 表示无命中）。 */
    public record TemplateFingerprint(String titleSelector, String metaContainer, String contentSelector) {}

    /** 探测页面命中的选择器，供 OnlineCrawlDiagnostic 等诊断工具做模板分布统计。 */
    public TemplateFingerprint detectTemplate(Document doc) {
        String titleSel = null;
        for (String sel : TITLE_SELECTORS) {
            Element el = doc.selectFirst(sel);
            if (el != null) {
                String text = el.ownText().trim();
                if (!StringUtils.hasText(text)) text = el.text().trim();
                if (StringUtils.hasText(text)) { titleSel = sel; break; }
            }
        }
        if (titleSel == null) {
            Element body = doc.selectFirst(".v_news_content");
            if (body != null && findNearestHeading(body) != null) titleSel = "heuristic:nearest-heading";
            else if (StringUtils.hasText(doc.title())) titleSel = "fallback:<title>";
        }

        String metaSel = null;
        for (String sel : META_CONTAINERS) {
            for (Element container : doc.select(sel)) {
                if (extractByLabels(container, AUTHOR_LABELS) != null
                        || extractByLabels(container, TIME_LABELS) != null) {
                    metaSel = sel; break;
                }
            }
            if (metaSel != null) break;
        }

        String contentSel = null;
        for (String sel : new String[]{"[id^=vsb_content] .v_news_content", ".v_news_content", "[id^=vsb_content]"}) {
            if (doc.selectFirst(sel) != null) { contentSel = sel; break; }
        }
        if (contentSel == null) {
            for (String sel : new String[]{".wp_articlecontent", ".article-content", ".content-body", ".cnt_p", ".article_text", ".detail-content", "#content"}) {
                if (doc.selectFirst(sel) != null) { contentSel = sel; break; }
            }
        }

        return new TemplateFingerprint(titleSel, metaSel, contentSel);
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
            String author = parseMetaField(doc, AUTHOR_LABELS);
            String publishTime = parseMetaField(doc, TIME_LABELS);
            if (publishTime != null) publishTime = normalizeDate(publishTime);
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
                    .content(htmlContent)
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

    // ==================== 字段解析 ====================

    private String parseTitle(Document doc) {
        for (String sel : TITLE_SELECTORS) {
            Element el = doc.selectFirst(sel);
            if (el != null) {
                String text = el.ownText().trim();
                if (!StringUtils.hasText(text)) text = el.text().trim();
                if (StringUtils.hasText(text)) return text;
            }
        }
        // 兜底：紧挨 .v_news_content 的最近 h1/h2/h3 祖先或兄弟
        Element body = doc.selectFirst(".v_news_content");
        if (body != null) {
            Element heading = findNearestHeading(body);
            if (heading != null) {
                String t = heading.text().trim();
                if (StringUtils.hasText(t)) return t;
            }
        }
        // 最后回退到 <title>。某些页面 Jsoup 的 doc.title() 会返回空串（head 结构异常），
        // 补一层 selectFirst 兜底。
        String t = doc.title();
        if (!StringUtils.hasText(t)) {
            Element titleEl = doc.selectFirst("head > title, title");
            if (titleEl != null) t = titleEl.text();
        }
        if (StringUtils.hasText(t)) {
            // 从后往前砍尾部的"-部门名"（可能多层），只保留真正的标题
            String cleaned = t.trim();
            for (int i = 0; i < 3; i++) {
                int idx = cleaned.lastIndexOf("-");
                if (idx <= 0) break;
                String tail = cleaned.substring(idx + 1).trim();
                // 尾部是"xxx站""学院""部""中心""网"等站点名，砍掉
                if (tail.length() > 30) break;
                cleaned = cleaned.substring(0, idx).trim();
            }
            return cleaned;
        }
        return null;
    }

    /** 在正文祖先链中往上走，找最近的 h1/h2/h3。若祖先里没有，取祖先之前最近的兄弟标题。 */
    private Element findNearestHeading(Element body) {
        Element p = body;
        while (p != null) {
            Element h = p.selectFirst("h1, h2, h3");
            if (h != null && !h.text().trim().isEmpty()) return h;
            Element prev = p.previousElementSibling();
            while (prev != null) {
                if (prev.tagName().matches("h[1-3]")) return prev;
                Element nested = prev.selectFirst("h1, h2, h3");
                if (nested != null) return nested;
                prev = prev.previousElementSibling();
            }
            p = p.parent();
        }
        return null;
    }

    /**
     * 从 META_CONTAINERS 中找到首个包含指定标签之一的文本块，提取冒号之后、下一段之前的值。
     * 对于 `p.info > span.ly/span.time1` 这种结构化容器，会直接取对应 class span 的文本。
     */
    private String parseMetaField(Document doc, String[] labels) {
        // 结构化快速路径：特定 class 直接命中
        if (sameLabels(labels, AUTHOR_LABELS)) {
            Element ly = doc.selectFirst("p.info span.ly, span.ly");
            if (ly != null) {
                String t = stripLabels(ly.text().trim(), labels);
                if (StringUtils.hasText(t)) return t;
            }
            Element author = doc.selectFirst("div.parameter div.author");
            if (author != null) {
                String raw = author.text().trim();
                String cleaned = stripLabels(raw, labels);
                // 仅当提取出作者标签后才返回，避免把"点击数"误当作者
                if (StringUtils.hasText(cleaned) && !cleaned.equals(raw)) return cleaned;
            }
        } else if (sameLabels(labels, TIME_LABELS)) {
            Element t1 = doc.selectFirst("p.info span.time1, span.time1, div.details_date, div.parameter div.date");
            if (t1 != null) {
                Matcher m = DATE_PATTERN.matcher(t1.text());
                if (m.find()) return m.group(1);
            }
        }

        // 通用路径：遍历容器，按标签取文本
        for (String sel : META_CONTAINERS) {
            for (Element container : doc.select(sel)) {
                String value = extractByLabels(container, labels);
                if (StringUtils.hasText(value)) return value;
            }
        }
        return null;
    }

    /** 在容器里按 "标签：值" 形式提取；若是时间字段，再用正则兜底。 */
    private String extractByLabels(Element container, String[] labels) {
        // 优先逐 span / div 命中（精确分隔）
        for (Element child : container.select("span, div, p, li")) {
            String text = child.ownText();
            if (!StringUtils.hasText(text)) text = child.text();
            String value = matchLabel(text, labels);
            if (StringUtils.hasText(value)) return value;
        }
        // 退回整段文本
        String text = container.text();
        String value = matchLabel(text, labels);
        if (StringUtils.hasText(value)) return value;
        // 时间字段：直接扫日期正则
        if (sameLabels(labels, TIME_LABELS)) {
            Matcher m = DATE_PATTERN.matcher(text);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private String matchLabel(String text, String[] labels) {
        if (text == null) return null;
        for (String label : labels) {
            int idx = text.indexOf(label + "：");
            if (idx < 0) idx = text.indexOf(label + ":");
            if (idx < 0) continue;
            int start = idx + label.length() + 1;
            // 跳过全角空格
            while (start < text.length() && (text.charAt(start) == ' ' || text.charAt(start) == '\u00a0' || text.charAt(start) == '\u3000')) {
                start++;
            }
            int end = start;
            // 值结束于：下一个标签、多个空格、换行
            while (end < text.length()) {
                char c = text.charAt(end);
                if (c == '\n' || c == '\r' || c == '\t') break;
                // 若当前位置开始是另一个已知标签（含冒号），停止
                if (startsWithAnyLabel(text, end, labels) || startsWithAnyLabel(text, end, AUTHOR_LABELS)
                        || startsWithAnyLabel(text, end, TIME_LABELS)) break;
                end++;
            }
            String v = text.substring(start, end).trim();
            // 裁掉尾部悬挂标点
            v = v.replaceAll("[\\s\u00a0\u3000]+$", "");
            if (!v.isEmpty()) return v;
        }
        return null;
    }

    private boolean startsWithAnyLabel(String text, int pos, String[] labels) {
        for (String label : labels) {
            if (text.startsWith(label + "：", pos) || text.startsWith(label + ":", pos)) return true;
        }
        return false;
    }

    private boolean sameLabels(String[] a, String[] b) {
        return a == b;
    }

    private String stripLabels(String text, String[] labels) {
        if (text == null) return null;
        for (String label : labels) {
            text = text.replaceFirst("^\\s*" + Pattern.quote(label) + "[：:]\\s*", "");
        }
        return text.trim();
    }

    private String normalizeDate(String raw) {
        if (raw == null) return null;
        String v = raw.replace("年", "-").replace("月", "-").replace("日", "").replace("/", "-");
        return v.trim();
    }

    private String parseHtmlContent(Document doc, String baseUrl) {
        // `#vsb_content` 可能带数字后缀（vsb_content_1081 等），用属性前缀匹配
        Element el = doc.selectFirst("[id^=vsb_content] .v_news_content");
        if (el == null) el = doc.selectFirst(".v_news_content");
        if (el == null) el = doc.selectFirst("[id^=vsb_content]");
        if (el == null) {
            for (String sel : new String[]{".wp_articlecontent", ".article-content", ".content-body", ".cnt_p", ".article_text", ".detail-content", "#content"}) {
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
            elems = doc.select(
                    ".attachment a, " +
                    "a[href$=.pdf], a[href$=.doc], a[href$=.docx], a[href$=.xls], a[href$=.xlsx], a[href$=.zip], " +
                    // 博达 VSB 的附件下载 URL 格式（cwb-xxgk 等 iframe/PDF 页面用）
                    "a[href*=DownloadAttachUrl], a[href*=download.jsp]"
            );
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
        for (Element img : c.select("img")) {
            String s = img.attr("src");
            if (!s.startsWith("http") && !s.startsWith("data:")) {
                img.attr("src", s.startsWith("/") ? baseUrl + s : baseUrl + "/" + s.replaceAll("^\\.\\./+", ""));
            }
        }
        for (Element a : c.select("a")) {
            String h = a.attr("href");
            if (!h.startsWith("http") && !h.startsWith("#") && !h.startsWith("javascript:")) {
                a.attr("href", h.startsWith("/") ? baseUrl + h : baseUrl + "/" + h);
            }
        }
        return c.html();
    }

    private String extractId(String url) { if (url == null) return null; Matcher m = ID_PATTERN.matcher(url); return m.find() ? m.group(1) : null; }
    private String inferType(String url, String name) {
        String l = (url + " " + name).toLowerCase();
        if (l.contains(".pdf")) return "pdf";
        if (l.contains(".doc")) return "word";
        if (l.contains(".xls")) return "excel";
        if (l.contains(".ppt")) return "ppt";
        if (l.contains(".zip") || l.contains(".rar") || l.contains(".7z")) return "archive";
        // 图片走前端的 previewImage（无 cookie 路径，依赖 /proxy/image 兜底）
        if (l.contains(".jpg") || l.contains(".jpeg") || l.contains(".png")
                || l.contains(".gif") || l.contains(".webp") || l.contains(".bmp")) return "image";
        return "file";
    }
    // 只在 title 含典型错误关键字，或页面含 VSB 错误容器 .prompt_up/.prompt_down 时判为错误页。
    // 不再扫 body text 找"请登录"——正文里常常提到"请登录 XX 系统"是招生/选课通知，不是登录墙。
    private boolean isErrorPage(Document doc) {
        String t = doc.title().toLowerCase();
        if (t.contains("error") || t.contains("错误") || t.contains("404")
                || t.equals("登录") || t.contains("请登录")
                || t.contains("系统提示")) {
            return true;
        }
        return doc.selectFirst("div.prompt_up, div.prompt_down") != null;
    }
}
