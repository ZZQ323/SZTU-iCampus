package cn.edu.sztui.base.infrastructure.util.praser;

import cn.edu.sztui.base.application.vo.AnnouncementContentVo;
import cn.edu.sztui.base.application.vo.AnnouncementContentVo.Attachment;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公告详情页解析器
 * <p>
 * 解析学校公文通详情页 HTML，提取公告内容
 */
@Slf4j
@Component
public class AnnouncementContentParser {

    /** 发布时间正则 */
    private static final Pattern PUBLISH_TIME_PATTERN =
            Pattern.compile("发布时间[：:]\\s*(\\d{4}年\\d{1,2}月\\d{1,2}日[\\s\\d:]*)", Pattern.UNICODE_CASE);

    /** 作者正则 */
    private static final Pattern AUTHOR_PATTERN =
            Pattern.compile("作者[：:]\\s*([^<\\s]+)", Pattern.UNICODE_CASE);

    /** ID 提取正则 */
    private static final Pattern ID_PATTERN = Pattern.compile("(\\d+)\\.htm");

    /** WebVPN 基础 URL */
    private static final String WEBVPN_BASE = "https://nbw-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118";

    /**
     * 解析详情页 HTML
     *
     * @param html 详情页完整 HTML
     * @param id 公告 ID
     * @return 公告详情内容
     */
    public AnnouncementContentVo parse(String html, String id) {
        AnnouncementContentVo content = new AnnouncementContentVo();
        content.setId(id);

        try {
            Document doc = Jsoup.parse(html);

            // 1. 标题
            Element titleEl = doc.selectFirst("h1.article-title");
            if (titleEl != null) {
                content.setTitle(titleEl.text().trim());
            } else {
                // 备选：从 title 标签提取
                Element titleTag = doc.selectFirst("title");
                if (titleTag != null) {
                    String title = titleTag.text();
                    if (title.contains("-")) {
                        title = title.substring(0, title.lastIndexOf("-")).trim();
                    }
                    content.setTitle(title);
                }
            }

            // 2. 作者和发布时间
            Element metaEl = doc.selectFirst(".article-sm");
            if (metaEl != null) {
                String metaText = metaEl.text();

                // 提取作者
                Matcher authorMatcher = AUTHOR_PATTERN.matcher(metaText);
                if (authorMatcher.find()) {
                    content.setAuthor(authorMatcher.group(1));
                }

                // 提取发布时间
                Matcher timeMatcher = PUBLISH_TIME_PATTERN.matcher(metaText);
                if (timeMatcher.find()) {
                    content.setPublishTime(timeMatcher.group(1));
                }
            }

            // 3. 正文内容
            Element contentEl = doc.selectFirst("#vsb_content");
            if (contentEl == null) {
                contentEl = doc.selectFirst(".v_news_content");
            }
            if (contentEl != null) {
                // 清洗 HTML，保留基本格式
                String cleanHtml = cleanContentHtml(contentEl.html());
                content.setContent(cleanHtml);
                // 提取纯文本
                content.setPlainText(contentEl.text());
            }

            // 4. 附件
            content.setAttachments(parseAttachments(doc));

            // 5. 上一篇/下一篇
            parseNavigationLinks(doc, content);

            // 6. 缓存时间
            content.setCachedAt(System.currentTimeMillis());

            log.info("解析公告详情: id={}, title={}, attachments={}",
                    id, content.getTitle(),
                    content.getAttachments() != null ? content.getAttachments().size() : 0);

        } catch (Exception e) {
            log.error("解析公告详情失败: id={}", id, e);
        }

        return content;
    }

    /**
     * 解析附件列表
     */
    private List<Attachment> parseAttachments(Document doc) {
        List<Attachment> attachments = new ArrayList<>();

        // 尝试多种可能的附件容器
        Elements attachItems = doc.select("ul.fujian li");
        if (attachItems.isEmpty()) {
            attachItems = doc.select(".fujian a");
        }
        if (attachItems.isEmpty()) {
            attachItems = doc.select(".attachment a");
        }

        for (Element item : attachItems) {
            Element link = item.tagName().equals("a") ? item : item.selectFirst("a");
            if (link != null) {
                Attachment attachment = new Attachment();
                attachment.setName(link.text().trim());

                String href = link.attr("href");
                // 处理相对路径
                if (href.startsWith("/")) {
                    attachment.setUrl(WEBVPN_BASE + href);
                } else if (!href.startsWith("http")) {
                    attachment.setUrl(WEBVPN_BASE + "/" + href);
                } else {
                    attachment.setUrl(href);
                }

                attachments.add(attachment);
            }
        }

        return attachments;
    }

    /**
     * 解析上一篇/下一篇导航链接
     */
    private void parseNavigationLinks(Document doc, AnnouncementContentVo content) {
        Element linkDiv = doc.selectFirst(".article-link");
        if (linkDiv == null) {
            linkDiv = doc.selectFirst(".page-link");
        }

        if (linkDiv != null) {
            // 上一篇
            Element prevEl = linkDiv.selectFirst("p:contains(上一篇) a");
            if (prevEl == null) {
                prevEl = linkDiv.selectFirst("a:contains(上一篇)");
            }
            if (prevEl == null) {
                // 尝试通过相邻元素查找
                Element prevP = linkDiv.selectFirst("p:contains(上一篇)");
                if (prevP != null) {
                    prevEl = prevP.selectFirst("a");
                }
            }

            if (prevEl != null && !prevEl.text().contains("没有了")) {
                content.setPrevId(extractIdFromHref(prevEl.attr("href")));
                content.setPrevTitle(prevEl.text().trim());
            }

            // 下一篇
            Element nextEl = linkDiv.selectFirst("p:contains(下一篇) a");
            if (nextEl == null) {
                nextEl = linkDiv.selectFirst("a:contains(下一篇)");
            }
            if (nextEl == null) {
                Element nextP = linkDiv.selectFirst("p:contains(下一篇)");
                if (nextP != null) {
                    nextEl = nextP.selectFirst("a");
                }
            }

            if (nextEl != null && !nextEl.text().contains("没有了")) {
                content.setNextId(extractIdFromHref(nextEl.attr("href")));
                content.setNextTitle(nextEl.text().trim());
            }
        }
    }

    /**
     * 清洗正文 HTML
     * <p>
     * 移除脚本、样式等，保留基本格式标签
     */
    private String cleanContentHtml(String html) {
        // 定义允许的标签和属性
        Safelist safelist = Safelist.relaxed()
                .addAttributes(":all", "style", "class")
                .addTags("span", "div", "p", "br", "hr", "table", "tr", "td", "th", "thead", "tbody")
                .preserveRelativeLinks(true);

        String clean = Jsoup.clean(html, safelist);

        // 移除连续空行
        clean = clean.replaceAll("(?m)^\\s*$[\n\r]{1,}", "\n");

        return clean;
    }

    /**
     * 从 href 提取公告 ID
     */
    private String extractIdFromHref(String href) {
        if (href == null) return null;
        Matcher m = ID_PATTERN.matcher(href);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}