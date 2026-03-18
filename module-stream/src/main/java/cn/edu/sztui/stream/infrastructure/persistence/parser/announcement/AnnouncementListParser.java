package cn.edu.sztui.stream.infrastructure.persistence.parser.announcement;

import cn.edu.sztui.stream.infrastructure.persistence.entity.textDTO.AnnouncementMetaVo;
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
 * 公告列表页 HTML 解析器（修复版）
 * <p>
 * 适配实际 HTML 结构：
 * - ul.news-ul > li.clearfix
 * - width02: 类别
 * - width03: 发文单位
 * - width04: 标题+链接
 * - width06: 日期
 */
@Slf4j
@Component
public class AnnouncementListParser {

    /**
     * 分类映射
     */
    private static final Map<String, String> CATEGORY_MAP = new LinkedHashMap<>();

    static {
        CATEGORY_MAP.put("1018", "教务");
        CATEGORY_MAP.put("1019", "科研");
        CATEGORY_MAP.put("1020", "行政");
        CATEGORY_MAP.put("1021", "学工");
        CATEGORY_MAP.put("1022", "校园");
    }

    /**
     * 从 href 提取 ID：info/1020/43371.htm → 43371
     */
    private static final Pattern ID_PATTERN = Pattern.compile("info/\\d+/(\\d+)\\.htm");

    /**
     * 从 href 提取分类：info/1020/43371.htm → 1020
     */
    private static final Pattern CATEGORY_FROM_HREF_PATTERN = Pattern.compile("info/(\\d+)/\\d+\\.htm");

    /**
     * 从 href 提取 wbtreeid：wbtreeid=1020 → 1020
     */
    private static final Pattern CATEGORY_FROM_TREEID_PATTERN = Pattern.compile("wbtreeid=(\\d+)");

    /**
     * 从分页链接提取 totalpage
     */
    private static final Pattern TOTALPAGE_PATTERN = Pattern.compile("totalpage=(\\d+)");

    /**
     * 解析列表页 HTML
     */
    public List<AnnouncementMetaVo> parseList(String html) {
        List<AnnouncementMetaVo> result = new ArrayList<>();

        try {
            Document doc = Jsoup.parse(html);

            // ⭐ 修正选择器：ul.news-ul 而不是 ul.news-list
            Elements items = doc.select("ul.news-ul > li.clearfix");

            if (items.isEmpty()) {
                // 备用选择器
                items = doc.select("ul.news-list > li.clearfix");
            }

            log.debug("找到 {} 个列表项", items.size());

            for (Element item : items) {
                try {
                    AnnouncementMetaVo meta = parseListItem(item);
                    if (meta != null && meta.getId() != null && !meta.getId().isEmpty()) {
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

        // 1. 解析类别（width02）
        Element categoryElem = item.selectFirst(".width02 a");
        if (categoryElem != null) {
            // 类别名称
            String categoryName = categoryElem.text().trim();
            meta.setCategoryName(categoryName);

            // 从 href 中提取类别代码
            String href = categoryElem.attr("href");
            Matcher matcher = CATEGORY_FROM_TREEID_PATTERN.matcher(href);
            if (matcher.find()) {
                meta.setCategoryCode(matcher.group(1));
            } else {
                // 反向查找
                for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
                    if (entry.getValue().equals(categoryName)) {
                        meta.setCategoryCode(entry.getKey());
                        break;
                    }
                }
            }
        }

        // 2. 解析发文单位（width03）
        Element deptElem = item.selectFirst(".width03 a");
        if (deptElem != null) {
            meta.setDepartment(deptElem.text().trim());
        }

        // 3. 解析标题和 ID（width04）
        Element titleLink = item.selectFirst(".width04 a");
        if (titleLink != null) {
            // 标题
            meta.setTitle(titleLink.text().trim());

            // 从 href 提取 ID
            String href = titleLink.attr("href");
            Matcher idMatcher = ID_PATTERN.matcher(href);
            if (idMatcher.find()) {
                meta.setId(idMatcher.group(1));
            }

            // 如果类别未获取到，从 href 中提取
            if (meta.getCategoryCode() == null || meta.getCategoryCode().isEmpty()) {
                Matcher catMatcher = CATEGORY_FROM_HREF_PATTERN.matcher(href);
                if (catMatcher.find()) {
                    String categoryCode = catMatcher.group(1);
                    meta.setCategoryCode(categoryCode);
                    meta.setCategoryName(CATEGORY_MAP.getOrDefault(categoryCode, "未知"));
                }
            }
        }

        // 4. 解析发布日期（width06）
        Element dateElem = item.selectFirst(".width06");
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

            // 方法1：从分页链接中提取 totalpage 参数
            Elements pageLinks = doc.select(".p_pages a");
            for (Element link : pageLinks) {
                String href = link.attr("href");
                Matcher matcher = TOTALPAGE_PATTERN.matcher(href);
                if (matcher.find()) {
                    int totalPage = Integer.parseInt(matcher.group(1));
                    log.info("从分页链接解析到总页数: {}", totalPage);
                    return totalPage;
                }
            }

            // 方法2：从"共XXX条"计算
            Elements totalElems = doc.select(".p_t");
            for (Element elem : totalElems) {
                String text = elem.text();
                Matcher matcher = Pattern.compile("共(\\d+)条").matcher(text);
                if (matcher.find()) {
                    int totalCount = Integer.parseInt(matcher.group(1));
                    int pageSize = 20;
                    int totalPage = (totalCount + pageSize - 1) / pageSize;
                    log.info("根据总条数计算总页数: {} 条 → {} 页", totalCount, totalPage);
                    return totalPage;
                }
            }

            log.warn("无法解析总页数，返回默认值 1");
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