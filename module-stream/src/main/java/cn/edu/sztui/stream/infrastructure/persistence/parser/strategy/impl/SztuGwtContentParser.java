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
import org.jsoup.safety.Safelist;
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
 * 解析 https://gwt.sztu.edu.cn/info/{category}/{id}.htm 页面
 */
@Slf4j
@Component
public class SztuGwtContentParser implements ParserStrategy {

    /** 解析器类型标识（与列表解析器相同） */
    public static final String TYPE = "sztu-gwt";

    /** 公文通基础URL */
    private static final String BASE_URL = "https://gwt.sztu.edu.cn";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public ListParserResult parseList(String html, SourceConfig sourceConfig, int page) {
        // 详情解析器不实现列表解析
        return ListParserResult.fail("请使用 SztuGwtListParser 解析列表");
    }

    @Override
    public ContentParserResult parseContent(String html, SourceConfig sourceConfig, String itemId) {
        if (!StringUtils.hasText(html)) {
            return ContentParserResult.fail("HTML 内容为空");
        }

        try {
            Document doc = Jsoup.parse(html);

            // 检查是否是错误页面
            if (isErrorPage(doc)) {
                return ContentParserResult.fail("访问被拒绝或需要登录");
            }

            // 解析标题
            String title = parseTitle(doc);

            // 解析发布信息（作者、时间）
            String author = parseAuthor(doc);
            String publishTime = parsePublishTime(doc);

            // 解析正文
            String htmlContent = parseHtmlContent(doc);
            String plainText = Jsoup.clean(htmlContent, Safelist.none());

            // 解析附件
            List<AttachmentInfo> attachments = parseAttachments(doc);

            // 解析上下篇
            String[] prevInfo = parsePrevArticle(doc);
            String[] nextInfo = parseNextArticle(doc);

            ContentParserResult result = ContentParserResult.builder()
                    .success(true)
                    .id(itemId)
                    .title(title)
                    .author(author)
                    .publishTime(publishTime)
                    .htmlContent(htmlContent)
                    .plainText(plainText)
                    .attachments(attachments)
                    .prevId(prevInfo[0])
                    .prevTitle(prevInfo[1])
                    .nextId(nextInfo[0])
                    .nextTitle(nextInfo[1])
                    .build();

            log.debug("解析详情成功 - ID: {}, 标题: {}, 附件数: {}",
                    itemId, title, attachments.size());

            return result;

        } catch (Exception e) {
            log.error("解析详情失败 - ID: {}", itemId, e);
            return ContentParserResult.fail("解析失败: " + e.getMessage());
        }
    }

    @Override
    public String buildDetailUrl(SourceConfig sourceConfig, String itemId, String itemUrl) {
        if (itemUrl != null && itemUrl.startsWith("http")) {
            return itemUrl;
        }
        String category = sourceConfig.getCategory();
        return BASE_URL + "/info/" + category + "/" + itemId + ".htm";
    }

    /**
     * 解析标题
     */
    private String parseTitle(Document doc) {
        // 方法1: 文章标题元素
        Element titleElem = doc.selectFirst("h1.arti-title, h1.title, .article-title h1, .news-title");
        if (titleElem != null) {
            return titleElem.text().trim();
        }

        // 方法2: 页面标题
        String pageTitle = doc.title();
        if (StringUtils.hasText(pageTitle)) {
            // 移除网站名称后缀
            int idx = pageTitle.lastIndexOf("-");
            if (idx > 0) {
                return pageTitle.substring(0, idx).trim();
            }
            return pageTitle.trim();
        }

        return null;
    }

    /**
     * 解析作者/发文单位
     */
    private String parseAuthor(Document doc) {
        Element authorElem = doc.selectFirst(".arti-metas span:contains(来源), .article-source, .source, .author");
        if (authorElem != null) {
            String text = authorElem.text();
            // 移除 "来源：" 前缀
            return text.replaceFirst("^(来源|作者|发布单位)[：:]\\s*", "").trim();
        }
        return null;
    }

    /**
     * 解析发布时间
     */
    private String parsePublishTime(Document doc) {
        // 方法1: 专门的时间元素
        Element timeElem = doc.selectFirst(".arti-metas span:contains(时间), .article-time, .publish-time, time");
        if (timeElem != null) {
            String text = timeElem.text();
            return extractDateTime(text);
        }

        // 方法2: 从元数据区域提取
        Element metaElem = doc.selectFirst(".arti-metas, .article-meta, .news-info");
        if (metaElem != null) {
            return extractDateTime(metaElem.text());
        }

        return null;
    }

    /**
     * 从文本中提取日期时间
     */
    private String extractDateTime(String text) {
        // 匹配 yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd HH:mm 或 yyyy-MM-dd
        Pattern pattern = Pattern.compile("(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}(\\s+\\d{1,2}:\\d{2}(:\\d{2})?)?)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).replace("/", "-");
        }
        return null;
    }

    /**
     * 解析 HTML 正文
     */
    private String parseHtmlContent(Document doc) {
        // 尝试多个可能的正文容器
        String[] selectors = {
                ".wp_articlecontent",
                ".article-content",
                ".arti-content",
                ".news-content",
                ".content",
                "#content",
                "article"
        };

        for (String selector : selectors) {
            Element contentElem = doc.selectFirst(selector);
            if (contentElem != null && StringUtils.hasText(contentElem.text())) {
                // 清洗 HTML
                return cleanHtml(contentElem);
            }
        }

        // 如果都找不到，返回 body 内容
        Element body = doc.body();
        if (body != null) {
            // 移除导航、页脚等
            body.select("nav, header, footer, script, style, .nav, .header, .footer").remove();
            return cleanHtml(body);
        }

        return "";
    }

    /**
     * 清洗 HTML 内容
     */
    private String cleanHtml(Element element) {
        // 克隆以避免修改原文档
        Element clone = element.clone();

        // 移除脚本和样式
        clone.select("script, style, iframe").remove();

        // 移除空白元素
        clone.select("p:empty, div:empty, span:empty").remove();

        // 处理图片：转换为绝对路径
        clone.select("img").forEach(img -> {
            String src = img.attr("src");
            if (!src.startsWith("http") && !src.startsWith("data:")) {
                if (src.startsWith("/")) {
                    img.attr("src", BASE_URL + src);
                } else {
                    img.attr("src", BASE_URL + "/" + src);
                }
            }
        });

        // 处理链接：转换为绝对路径
        clone.select("a").forEach(a -> {
            String href = a.attr("href");
            if (!href.startsWith("http") && !href.startsWith("#") && !href.startsWith("javascript:")) {
                if (href.startsWith("/")) {
                    a.attr("href", BASE_URL + href);
                } else {
                    a.attr("href", BASE_URL + "/" + href);
                }
            }
        });

        return clone.html();
    }

    /**
     * 解析附件列表
     */
    private List<AttachmentInfo> parseAttachments(Document doc) {
        List<AttachmentInfo> attachments = new ArrayList<>();

        // 方法1: 专门的附件区域
        Elements attachElems = doc.select(".attachment a, .fujian a, .file-list a, ul.attach li a");

        // 方法2: 正文中的下载链接
        if (attachElems.isEmpty()) {
            attachElems = doc.select("a[href*=download], a[href*=attachment], a[href$=.pdf], " +
                    "a[href$=.doc], a[href$=.docx], a[href$=.xls], a[href$=.xlsx], " +
                    "a[href$=.ppt], a[href$=.pptx], a[href$=.zip], a[href$=.rar]");
        }

        for (Element a : attachElems) {
            String name = a.text().trim();
            String url = a.attr("href");

            if (!StringUtils.hasText(name) || !StringUtils.hasText(url)) {
                continue;
            }

            // 转换为绝对路径
            if (!url.startsWith("http")) {
                if (url.startsWith("/")) {
                    url = BASE_URL + url;
                } else {
                    url = BASE_URL + "/" + url;
                }
            }

            // 推断文件类型
            String type = inferFileType(url, name);

            attachments.add(AttachmentInfo.builder()
                    .name(name)
                    .url(url)
                    .type(type)
                    .build());
        }

        return attachments;
    }

    /**
     * 推断文件类型
     */
    private String inferFileType(String url, String name) {
        String lowerUrl = url.toLowerCase();
        String lowerName = name.toLowerCase();

        if (lowerUrl.endsWith(".pdf") || lowerName.endsWith(".pdf")) return "pdf";
        if (lowerUrl.endsWith(".doc") || lowerUrl.endsWith(".docx") ||
                lowerName.endsWith(".doc") || lowerName.endsWith(".docx")) return "word";
        if (lowerUrl.endsWith(".xls") || lowerUrl.endsWith(".xlsx") ||
                lowerName.endsWith(".xls") || lowerName.endsWith(".xlsx")) return "excel";
        if (lowerUrl.endsWith(".ppt") || lowerUrl.endsWith(".pptx") ||
                lowerName.endsWith(".ppt") || lowerName.endsWith(".pptx")) return "ppt";
        if (lowerUrl.endsWith(".zip") || lowerUrl.endsWith(".rar") ||
                lowerUrl.endsWith(".7z")) return "archive";
        if (lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg") ||
                lowerUrl.endsWith(".png") || lowerUrl.endsWith(".gif")) return "image";

        return "file";
    }

    /**
     * 解析上一篇
     */
    private String[] parsePrevArticle(Document doc) {
        Element prevElem = doc.selectFirst("a:contains(上一篇), a:contains(上一条), .prev-article a");
        if (prevElem != null) {
            String href = prevElem.attr("href");
            String title = prevElem.text().replaceFirst("^(上一篇|上一条)[：:]\\s*", "").trim();
            String id = extractIdFromUrl(href);
            return new String[]{id, title};
        }
        return new String[]{null, null};
    }

    /**
     * 解析下一篇
     */
    private String[] parseNextArticle(Document doc) {
        Element nextElem = doc.selectFirst("a:contains(下一篇), a:contains(下一条), .next-article a");
        if (nextElem != null) {
            String href = nextElem.attr("href");
            String title = nextElem.text().replaceFirst("^(下一篇|下一条)[：:]\\s*", "").trim();
            String id = extractIdFromUrl(href);
            return new String[]{id, title};
        }
        return new String[]{null, null};
    }

    /**
     * 从 URL 中提取 ID
     */
    private String extractIdFromUrl(String url) {
        if (url == null) return null;
        Pattern pattern = Pattern.compile("/(\\d+)\\.htm");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 检查是否是错误页面
     */
    private boolean isErrorPage(Document doc) {
        String title = doc.title().toLowerCase();
        String bodyText = doc.body() != null ? doc.body().text().toLowerCase() : "";

        if (title.contains("error") || title.contains("错误") ||
                title.contains("404") || title.contains("403")) {
            return true;
        }

        if (title.contains("登录") || title.contains("login") ||
                bodyText.contains("请登录") || bodyText.contains("please login")) {
            return true;
        }

        return false;
    }
}
