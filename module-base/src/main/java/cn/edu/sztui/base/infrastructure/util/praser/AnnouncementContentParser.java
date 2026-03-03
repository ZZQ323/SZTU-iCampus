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
 * 公告详情页 HTML 解析器
 */
@Slf4j
@Component
public class AnnouncementContentParser {

    private static final Pattern PUBLISH_TIME_PATTERN =
            Pattern.compile("发布时间[：:]\\s*(\\d{4}年\\d{1,2}月\\d{1,2}日\\s*\\d{1,2}:\\d{2})");

    public AnnouncementContentVo parse(String html, String id) {
        AnnouncementContentVo vo = new AnnouncementContentVo();
        vo.setId(id);

        try {
            Document doc = Jsoup.parse(html);

            // 标题
            Element titleElem = doc.selectFirst(".title h2");
            if (titleElem != null) {
                vo.setTitle(titleElem.text().trim());
            }

            // 元信息
            Element metaElem = doc.selectFirst(".article-sm");
            if (metaElem != null) {
                parseMetaInfo(metaElem, vo);
            }

            // 正文
            Element contentElem = doc.selectFirst(".article-con");
            if (contentElem != null) {
                contentElem.select("script, style").remove();

                // 处理图片路径
                for (Element img : contentElem.select("img")) {
                    String src = img.attr("src");
                    if (src.startsWith("/")) {
                        img.attr("src", "https://nbw-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118" + src);
                    }
                }

                vo.setContent(contentElem.html());
            }

            // 附件
            vo.setAttachments(parseAttachments(doc));

            // 上下篇导航
            parseNavigation(doc, vo);

        } catch (Exception e) {
            log.error("解析公告详情失败: id={}, error={}", id, e.getMessage());
        }

        return vo;
    }

    private void parseMetaInfo(Element metaElem, AnnouncementContentVo vo) {
        String text = metaElem.text();

        Matcher timeMatcher = PUBLISH_TIME_PATTERN.matcher(text);
        if (timeMatcher.find()) {
            vo.setPublishTime(timeMatcher.group(1));
        }

        for (Element span : metaElem.select("span")) {
            String spanText = span.text();
            if (spanText.startsWith("作者")) {
                vo.setAuthor(spanText.replace("作者：", "").replace("作者:", "").trim());
                break;
            }
        }
    }

    private List<AttachmentVo> parseAttachments(Document doc) {
        List<AttachmentVo> attachments = new ArrayList<>();

        for (Element li : doc.select(".fujian li")) {
            Element link = li.selectFirst("a");
            if (link != null) {
                AttachmentVo attachment = new AttachmentVo();
                attachment.setName(link.text().trim());

                String href = link.attr("href");
                if (href.startsWith("/")) {
                    href = "https://nbw-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118" + href;
                }
                attachment.setUrl(href);

                String name = attachment.getName().toLowerCase();
                if (name.endsWith(".pdf")) attachment.setType("pdf");
                else if (name.endsWith(".doc") || name.endsWith(".docx")) attachment.setType("word");
                else if (name.endsWith(".xls") || name.endsWith(".xlsx")) attachment.setType("excel");
                else attachment.setType("file");

                attachments.add(attachment);
            }
        }

        return attachments;
    }

    private void parseNavigation(Document doc, AnnouncementContentVo vo) {
        for (Element p : doc.select(".article-link p")) {
            String text = p.text();
            Element link = p.selectFirst("a");

            if (link != null) {
                String href = link.attr("href");
                Matcher matcher = Pattern.compile("(\\d+)\\.htm").matcher(href);
                if (matcher.find()) {
                    String navId = matcher.group(1);

                    if (text.contains("上一篇")) {
                        vo.setPrevId(navId);
                        vo.setPrevTitle(link.text().trim());
                    } else if (text.contains("下一篇")) {
                        vo.setNextId(navId);
                        vo.setNextTitle(link.text().trim());
                    }
                }
            }
        }
    }
}