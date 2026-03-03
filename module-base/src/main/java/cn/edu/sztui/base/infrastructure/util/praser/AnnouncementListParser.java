package cn.edu.sztui.base.infrastructure.util.praser;

import cn.edu.sztui.base.application.vo.AnnouncementMetaVo;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公告列表页 HTML 解析器
 */
@Slf4j
@Component
public class AnnouncementListParser {

    /** 分类映射 */
    private static final Map<String, String> CATEGORY_MAP = new LinkedHashMap<>();

    static {
        CATEGORY_MAP.put("1018", "教务");
        CATEGORY_MAP.put("1019", "科研");
        CATEGORY_MAP.put("1020", "行政");
        CATEGORY_MAP.put("1021", "学工");
        CATEGORY_MAP.put("1022", "校园");
    }

    /** 从 href 提取 ID 的正则：info/1018/49003.htm → 49003 */
    private static final Pattern ID_PATTERN = Pattern.compile("info/\\d+/(\\d+)\\.htm");

    /** 从 href 提取分类的正则 */
    private static final Pattern CATEGORY_PATTERN = Pattern.compile("info/(\\d+)/\\d+\\.htm");

    /** 从 href 提取 totalpage 的正则 */
    private static final Pattern TOTALPAGE_PATTERN = Pattern.compile("a1020514t=(\\d+)");

    /**
     * 解析列表页 HTML
     */
    public List<AnnouncementMetaVo> parseList(String html) {
        List<AnnouncementMetaVo> result = new ArrayList<>();

        try {
            Document doc = Jsoup.parse(html);
            Elements items = doc.select("ul.news-list > li.clearfix");

            for (Element item : items) {
                try {
                    AnnouncementMetaVo meta = parseListItem(item);
                    if (meta != null && meta.getId() != null) {
                        result.add(meta);
                    }
                } catch (Exception e) {
                    log.warn("解析列表项失败: {}", e.getMessage());
                }
            }

            log.debug("解析列表页完成，获取 {} 条记录", result.size());

        } catch (Exception e) {
            log.error("解析列表页 HTML 失败: {}", e.getMessage());
        }

        return result;
    }

    private AnnouncementMetaVo parseListItem(Element item) {
        AnnouncementMetaVo meta = new AnnouncementMetaVo();

        // 1. 解析分类
        Element categoryLink = item.selectFirst(".width01 a");
        if (categoryLink != null) {
            String href = categoryLink.attr("href");
            Matcher matcher = Pattern.compile("wbtreeid=(\\d+)").matcher(href);
            if (matcher.find()) {
                String categoryCode = matcher.group(1);
                meta.setCategory(categoryCode);
                meta.setCategoryName(CATEGORY_MAP.getOrDefault(categoryCode, "未知"));
            }
        }

        // 2. 解析发布部门
        Element deptElem = item.selectFirst(".width02 a");
        if (deptElem != null) {
            meta.setDepartment(deptElem.text().trim());
        }

        // 3. 解析标题和ID
        Element titleLink = item.selectFirst(".width03 a");
        if (titleLink != null) {
            meta.setTitle(titleLink.text().trim());

            String href = titleLink.attr("href");
            Matcher idMatcher = ID_PATTERN.matcher(href);
            if (idMatcher.find()) {
                meta.setId(idMatcher.group(1));
            }

            if (meta.getCategory() == null) {
                Matcher catMatcher = CATEGORY_PATTERN.matcher(href);
                if (catMatcher.find()) {
                    String categoryCode = catMatcher.group(1);
                    meta.setCategory(categoryCode);
                    meta.setCategoryName(CATEGORY_MAP.getOrDefault(categoryCode, "未知"));
                }
            }
        }

        // 4. 解析发布日期
        Element dateElem = item.selectFirst(".width04");
        if (dateElem != null) {
            meta.setPublishDate(dateElem.text().trim());
        }

        return meta;
    }

    /**
     * 解析总页数
     */
    public int parseTotalPage(String html) {
        try {
            Document doc = Jsoup.parse(html);

            Elements pageLinks = doc.select(".p_pages a");
            for (Element link : pageLinks) {
                String href = link.attr("href");
                Matcher matcher = TOTALPAGE_PATTERN.matcher(href);
                if (matcher.find()) {
                    int totalPage = Integer.parseInt(matcher.group(1));
                    log.info("解析到总页数: {}", totalPage);
                    return totalPage;
                }
            }

            Element totalElem = doc.selectFirst(".p_t");
            if (totalElem != null) {
                String text = totalElem.text();
                Matcher matcher = Pattern.compile("共(\\d+)条").matcher(text);
                if (matcher.find()) {
                    int totalCount = Integer.parseInt(matcher.group(1));
                    int pageSize = 20;
                    int totalPage = (totalCount + pageSize - 1) / pageSize;
                    log.info("根据总条数计算总页数: {} 条 → {} 页", totalCount, totalPage);
                    return totalPage;
                }
            }

            return 1;

        } catch (Exception e) {
            log.error("解析总页数失败: {}", e.getMessage());
            return 1;
        }
    }

    public static Map<String, String> getCategoryMap() {
        return Collections.unmodifiableMap(CATEGORY_MAP);
    }
}