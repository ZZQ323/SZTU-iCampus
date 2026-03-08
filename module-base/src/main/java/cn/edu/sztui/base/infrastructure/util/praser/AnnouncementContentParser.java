package cn.edu.sztui.base.infrastructure.util.praser;

import cn.edu.sztui.base.application.vo.AnnouncementContentVo;
import cn.edu.sztui.base.application.vo.AttachmentVo;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公告详情页 HTML 解析器（修正版）
 * <p>
 * 基于实际 HTML 结构：
 * <ul>
 *   <li>标题：h1.article-title</li>
 *   <li>元信息：div.article-sm（包含 "作者：xxx" 和 "发布时间：xxx"）</li>
 *   <li>正文：#vsb_content .v_news_content</li>
 *   <li>附件：ul.fujian li a</li>
 *   <li>导航：div.article-link p</li>
 * </ul>
 */
@Slf4j
@Component
public class AnnouncementContentParser {

    /**
     * 发布时间正则
     */
    private static final Pattern PUBLISH_TIME_PATTERN =
            Pattern.compile("发布时间[：:]\\s*(\\d{4}年\\d{1,2}月\\d{1,2}日\\s*\\d{1,2}:\\d{2})");

    /**
     * 详情页基础URL
     */
    private static final String BASE_URL = "https://nbw-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118";

    /**
     * 解析公告详情HTML
     *
     * @param html 原始HTML
     * @param id   公告ID
     * @return 解析后的结构化数据
     */
    public AnnouncementContentVo parse(String html, String id) {
        AnnouncementContentVo vo = new AnnouncementContentVo();
        vo.setId(id);
        vo.setCachedAt(System.currentTimeMillis());

        try {
            Document doc = Jsoup.parse(html);

            // 1. 解析标题（h1.article-title）
            parseTitle(doc, vo);

            // 2. 解析元信息（div.article-sm）- 作者和发布时间
            parseMetaInfo(doc, vo);

            // 3. 解析正文（#vsb_content .v_news_content）
            parseContent(doc, vo);

            // 4. 解析附件（ul.fujian li）
            vo.setAttachments(parseAttachments(doc));

            // 5. 解析上下篇导航（div.article-link）
            parseNavigation(doc, vo);

            log.info("解析公告详情成功: id={}, title={}, attachments={}",
                    id, vo.getTitle(), vo.getAttachments() != null ? vo.getAttachments().size() : 0);

        } catch (Exception e) {
            log.error("解析公告详情失败: id={}, error={}", id, e.getMessage(), e);
        }

        return vo;
    }

    /**
     * 解析标题
     * 选择器：h1.article-title
     */
    private void parseTitle(Document doc, AnnouncementContentVo vo) {
        // 优先使用 h1.article-title
        Element titleElem = doc.selectFirst("h1.article-title");

        if (titleElem == null) {
            // 备用：从 title 标签提取
            Element titleTag = doc.selectFirst("title");
            if (titleTag != null) {
                String titleText = titleTag.text();
                // 去掉网站名称后缀 "xxx-深圳技术大学"
                if (titleText.contains("-")) {
                    titleText = titleText.substring(0, titleText.lastIndexOf("-")).trim();
                }
                vo.setTitle(titleText);
                return;
            }
        }

        if (titleElem != null) {
            vo.setTitle(titleElem.text().trim());
        }
    }

    /**
     * 解析元信息（作者、发布时间）
     * 选择器：div.article-sm
     * 格式：作者：研究生院  发布时间：2025年10月11日 10:18
     */
    private void parseMetaInfo(Document doc, AnnouncementContentVo vo) {
        Element metaElem = doc.selectFirst("div.article-sm");
        if (metaElem == null) {
            metaElem = doc.selectFirst(".article-sm");
        }

        if (metaElem != null) {
            String text = metaElem.text();

            // 提取发布时间
            Matcher timeMatcher = PUBLISH_TIME_PATTERN.matcher(text);
            if (timeMatcher.find()) {
                vo.setPublishTime(timeMatcher.group(1));
            }

            // 提取作者 - 格式：作者：xxx 或 作者:xxx
            // 作者在 "作者：" 后面，到下一个空白或标签之前
            int authorStart = text.indexOf("作者：");
            if (authorStart == -1) {
                authorStart = text.indexOf("作者:");
            }
            if (authorStart != -1) {
                // 跳过 "作者：" 或 "作者:"
                int start = authorStart + 3;
                // 找到下一个空白字符或特殊字符
                int end = start;
                while (end < text.length()) {
                    char c = text.charAt(end);
                    if (c == ' ' || c == '\u00a0' || c == '\t' || c == '发') {
                        break;
                    }
                    end++;
                }
                if (end > start) {
                    vo.setAuthor(text.substring(start, end).trim());
                }
            }
        }
    }

    /**
     * 解析正文内容
     * 选择器：#vsb_content .v_news_content
     */
    private void parseContent(Document doc, AnnouncementContentVo vo) {
        // 使用正确的选择器
        Element contentElem = doc.selectFirst("#vsb_content .v_news_content");

        if (contentElem == null) {
            contentElem = doc.selectFirst(".v_news_content");
        }
        if (contentElem == null) {
            contentElem = doc.selectFirst("#vsb_content");
        }

        if (contentElem != null) {
            // 移除脚本和样式
            contentElem.select("script, style").remove();

            // 处理图片路径 - 转换为绝对路径
            for (Element img : contentElem.select("img")) {
                String src = img.attr("src");
                if (src.startsWith("/")) {
                    img.attr("src", BASE_URL + src);
                } else if (src.startsWith("../")) {
                    img.attr("src", BASE_URL + "/" + src.replaceAll("^\\.\\./+", ""));
                }
            }

            // 处理链接路径
            for (Element link : contentElem.select("a")) {
                String href = link.attr("href");
                if (href.startsWith("/")) {
                    link.attr("href", BASE_URL + href);
                }
            }

            // 获取处理后的HTML
            String contentHtml = contentElem.html();
            vo.setContent(contentHtml);

            // 生成纯文本摘要（用于搜索）
            String plainText = contentElem.text();
            if (plainText.length() > 500) {
                plainText = plainText.substring(0, 500) + "...";
            }
            vo.setPlainText(plainText);
        }
    }

    /**
     * 解析附件列表
     * 选择器：ul.fujian li
     * 格式：附件【<a href="...">研究生听课记录表.docx</a>】已下载xxx次
     */
    private List<AttachmentVo> parseAttachments(Document doc) {
        List<AttachmentVo> attachments = new ArrayList<>();

        for (Element li : doc.select("ul.fujian li, .fujian li, UL.fujian li")) {
            Element link = li.selectFirst("a");
            if (link != null) {
                AttachmentVo attachment = new AttachmentVo();

                // 提取附件名称
                String name = link.text().trim();

                // 如果 link 文本为空，尝试从 li 文本中提取 "附件【xxx】" 格式
                if (name.isEmpty()) {
                    String liText = li.text();
                    int start = liText.indexOf("【");
                    int end = liText.indexOf("】");
                    if (start != -1 && end > start) {
                        name = liText.substring(start + 1, end);
                    }
                }
                attachment.setName(name);

                // 处理下载链接
                String href = link.attr("href");
                if (href.startsWith("/")) {
                    href = BASE_URL + href;
                }
                attachment.setUrl(href);

                // 根据扩展名判断类型
                String nameLower = name.toLowerCase();
                if (nameLower.endsWith(".pdf")) {
                    attachment.setType("pdf");
                } else if (nameLower.endsWith(".doc") || nameLower.endsWith(".docx")) {
                    attachment.setType("word");
                } else if (nameLower.endsWith(".xls") || nameLower.endsWith(".xlsx")) {
                    attachment.setType("excel");
                } else if (nameLower.endsWith(".ppt") || nameLower.endsWith(".pptx")) {
                    attachment.setType("ppt");
                } else if (nameLower.endsWith(".zip") || nameLower.endsWith(".rar")) {
                    attachment.setType("archive");
                } else if (nameLower.endsWith(".jpg") || nameLower.endsWith(".png") || nameLower.endsWith(".gif")) {
                    attachment.setType("image");
                } else {
                    attachment.setType("file");
                }

                attachments.add(attachment);
                log.debug("解析到附件: name={}, url={}", attachment.getName(), attachment.getUrl());
            }
        }

        return attachments;
    }

    /**
     * 解析上下篇导航
     * 选择器：div.article-link p
     * 格式：
     * <p>上一篇：<a href="49764.htm">关于...</a></p>
     * <p>下一篇：<a href="49755.htm">关于...</a></p>
     */
    private void parseNavigation(Document doc, AnnouncementContentVo vo) {
        Element navDiv = doc.selectFirst("div.article-link");
        if (navDiv == null) {
            navDiv = doc.selectFirst(".article-link");
        }

        if (navDiv == null) {
            return;
        }

        for (Element p : navDiv.select("p")) {
            String text = p.text();
            Element link = p.selectFirst("a");

            if (link != null) {
                String href = link.attr("href");
                // 从 href 中提取 ID，格式如 "49764.htm"
                Matcher matcher = Pattern.compile("(\\d+)\\.htm").matcher(href);
                if (matcher.find()) {
                    String navId = matcher.group(1);
                    String navTitle = link.text().trim();

                    if (text.contains("上一篇")) {
                        vo.setPrevId(navId);
                        vo.setPrevTitle(navTitle);
                    } else if (text.contains("下一篇")) {
                        vo.setNextId(navId);
                        vo.setNextTitle(navTitle);
                    }
                }
            }
        }
    }
}