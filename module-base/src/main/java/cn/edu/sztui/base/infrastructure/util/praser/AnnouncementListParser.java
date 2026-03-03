package cn.edu.sztui.base.infrastructure.util.praser;

import cn.edu.sztui.base.application.vo.AnnouncementMetaVo;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公告列表页解析器
 * <p>
 * 解析学校公文通列表页 HTML，提取公告元数据
 */
@Slf4j
@Component
public class AnnouncementListParser {

    /** 分类代码 → 名称映射 */
    private static final Map<String, String> CATEGORY_MAP = Map.of(
            "1018", "教务",
            "1019", "科研",
            "1020", "行政",
            "1021", "学工",
            "1022", "校园",
            "1029", "全部"
    );

    /** URL 提取 ID 的正则：info/1018/49003.htm */
    private static final Pattern URL_PATTERN = Pattern.compile("info/(\\d+)/(\\d+)\\.htm");

    /** 总条数正则：共287条 */
    private static final Pattern TOTAL_PATTERN = Pattern.compile("共(\\d+)条");

    /** wbtreeid 提取正则 */
    private static final Pattern CATEGORY_PATTERN = Pattern.compile("wbtreeid=(\\d+)");

    /**
     * 解析列表页 HTML
     *
     * @param html 列表页完整 HTML
     * @return 公告元数据列表
     */
    public List<AnnouncementMetaVo> parseList(String html) {
        List<AnnouncementMetaVo> result = new ArrayList<>();

        try {
            Document doc = Jsoup.parse(html);
            Elements items = doc.select("ul.list-ul li.clearfix");

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

            log.info("成功解析 {} 条公告", result.size());

        } catch (Exception e) {
            log.error("解析公告列表页失败", e);
        }

        return result;
    }

    /**
     * 解析单个列表项
     * <p>
     * HTML 结构:
     * <pre>
     * &lt;li class="clearfix"&gt;
     *   &lt;div class="pull-left width01"&gt;&lt;a class="nav07" href="..."&gt;教务&lt;/a&gt;&lt;/div&gt;
     *   &lt;div class="pull-left width02 txt-elise"&gt;&lt;a href="info/1018/49003.htm"&gt;研究生院&lt;/a&gt;&lt;/div&gt;
     *   &lt;div class="pull-left width03 txt-elise text-left"&gt;&lt;a href="info/1018/49003.htm"&gt;标题...&lt;/a&gt;&lt;/div&gt;
     *   &lt;div class="pull-left width04"&gt;2025-06-19&lt;/div&gt;
     * &lt;/li&gt;
     * </pre>
     */
    private AnnouncementMetaVo parseListItem(Element item) {
        AnnouncementMetaVo meta = new AnnouncementMetaVo();

        // 1. 类别
        Element categoryEl = item.selectFirst(".width01 a");
        if (categoryEl != null) {
            meta.setCategoryName(categoryEl.text().trim());
            String categoryHref = categoryEl.attr("href");
            Matcher m = CATEGORY_PATTERN.matcher(categoryHref);
            if (m.find()) {
                meta.setCategory(m.group(1));
            }
        }

        // 2. 发文单位
        Element deptEl = item.selectFirst(".width02 a");
        if (deptEl != null) {
            meta.setDepartment(deptEl.text().trim());
        }

        // 3. 标题和链接
        Element titleEl = item.selectFirst(".width03 a");
        if (titleEl != null) {
            meta.setTitle(titleEl.text().trim());
            String href = titleEl.attr("href");
            meta.setUrl(href);

            Matcher matcher = URL_PATTERN.matcher(href);
            if (matcher.find()) {
                String category = matcher.group(1);
                String id = matcher.group(2);
                meta.setId(id);
                // 如果从类别链接未提取到分类，则从URL提取
                if (meta.getCategory() == null) {
                    meta.setCategory(category);
                    meta.setCategoryName(CATEGORY_MAP.getOrDefault(category, "未知"));
                }
            }
        }

        // 4. 发文日期
        Element dateEl = item.selectFirst(".width04");
        if (dateEl != null) {
            meta.setPublishDate(dateEl.text().trim());
        }

        // 5. 爬取时间
        meta.setCrawledAt(System.currentTimeMillis());

        return meta;
    }

    /**
     * 解析总条数
     *
     * @param html 列表页 HTML
     * @return 总条数，解析失败返回 -1
     */
    public int parseTotalCount(String html) {
        try {
            Document doc = Jsoup.parse(html);
            Element totalEl = doc.selectFirst(".p_t");
            if (totalEl != null) {
                Matcher matcher = TOTAL_PATTERN.matcher(totalEl.text());
                if (matcher.find()) {
                    return Integer.parseInt(matcher.group(1));
                }
            }
        } catch (Exception e) {
            log.warn("解析总条数失败: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * 获取分类名称
     *
     * @param code 分类代码
     * @return 分类名称
     */
    public static String getCategoryName(String code) {
        return CATEGORY_MAP.getOrDefault(code, "未知");
    }

    /**
     * 获取所有分类映射
     */
    public static Map<String, String> getCategoryMap() {
        return CATEGORY_MAP;
    }
}
