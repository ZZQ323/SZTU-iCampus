package cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl;

import cn.edu.sztui.stream.application.external.engine.ArticleUrlResolver;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.SourceConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ContentParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult.InfoItemMeta;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ParserStrategy;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 深圳技术大学公文通 - 列表解析器
 * <p>
 * ⭐ 修复：categoryCode 从文章 URL 中提取（info/1020/50838.htm → 1020），
 *    不再依赖 sourceConfig.getCategoryCode()（在"全部"页面该值不对应具体文章分类）
 */
@Slf4j
@Component
public class SztuGwtListParser implements ParserStrategy {

    public static final String TYPE = "sztu-gwt";

    private static final Map<String, String> CATEGORY_MAP = new HashMap<>();
    static {
        CATEGORY_MAP.put("1018", "教务");
        CATEGORY_MAP.put("1019", "科研");
        CATEGORY_MAP.put("1020", "行政");
        CATEGORY_MAP.put("1021", "学工");
        CATEGORY_MAP.put("1022", "校园");
    }

    /** 从 URL info/1020/50838.htm 提取 ID */
    private static final Pattern ID_FROM_URL = Pattern.compile("/(\\d+)\\.htm");

    /** ⭐ 从 URL info/1020/50838.htm 提取 categoryCode */
    private static final Pattern CATEGORY_FROM_URL = Pattern.compile("info/(\\d+)/\\d+\\.htm");

    /** 从分页链接提取 totalpage */
    private static final Pattern TOTALPAGE_PARAM = Pattern.compile("totalpage=(\\d+)");

    /** 从文本提取总条数 */
    private static final Pattern TOTAL_COUNT = Pattern.compile("共(\\d+)条");

    @Override
    public String getType() { return TYPE; }

    @Override
    public ListParserResult parseList(String html, SourceConfig sourceConfig, int page) {
        if (!StringUtils.hasText(html)) return ListParserResult.fail("HTML 内容为空");

        try {
            Document doc = Jsoup.parse(html);
            if (isErrorPage(doc)) return ListParserResult.fail("访问被拒绝或需要登录");

            List<InfoItemMeta> items = new ArrayList<>();
            Elements listItems = doc.select("ul.news-ul > li.clearfix");
            if (listItems.isEmpty()) listItems = doc.select("ul.news-list > li.clearfix");

            for (Element item : listItems) {
                InfoItemMeta meta = parseListItem(item, sourceConfig);
                if (meta != null && StringUtils.hasText(meta.getId())) {
                    items.add(meta);
                }
            }

            Integer totalPages = parseTotalPages(doc);

            log.debug("解析列表成功 - 数据源: {}, 页码: {}, 条目数: {}, 总页数: {}",
                    sourceConfig.getId(), page, items.size(), totalPages);

            return ListParserResult.builder()
                    .success(true)
                    .items(items)
                    .totalPages(totalPages)
                    .currentPage(page)
                    .hasMore(totalPages != null && page < totalPages)
                    .sourceId(sourceConfig.getId())
                    .build();

        } catch (Exception e) {
            log.error("解析列表失败 - 数据源: {}, 页码: {}", sourceConfig.getId(), page, e);
            return ListParserResult.fail("解析失败: " + e.getMessage());
        }
    }

    @Override
    public ContentParserResult parseContent(String html, SourceConfig sourceConfig, String itemId) {
        return ContentParserResult.fail("请使用 SztuGwtContentParser 解析详情");
    }

    @Override
    public String buildListUrl(SourceConfig sourceConfig, int page) {
        String cat = sourceConfig.getCategoryCode();
        if (page == 1) return sourceConfig.getBaseUrl() + "/info/" + cat + "/list.htm";
        return sourceConfig.getBaseUrl() + "/info/" + cat + "/list" + page + ".htm";
    }

    // ==================== 列表项解析 ====================

    private InfoItemMeta parseListItem(Element item, SourceConfig sourceConfig) {
        try {
            // 1. 标题和链接（width04）
            Element titleLink = item.selectFirst(".width04 a");
            if (titleLink == null) return null;

            String title = titleLink.text().trim();
            String href = titleLink.attr("href");   // 例：info/1020/50838.htm
            String id = extractIdFromUrl(href);
            if (id == null) return null;

            // ⭐ 2. categoryCode：从文章 URL 提取（最可靠）
            //    info/1020/50838.htm → "1020"
            String categoryCode = extractCategoryFromUrl(href);

            // 3. categoryName：从 .width02 提取，或从 CATEGORY_MAP 查
            String categoryName = null;
            Element catElem = item.selectFirst(".width02 a");
            if (catElem != null) {
                categoryName = catElem.text().trim();
                // 如果 URL 没提取到 categoryCode，从 .width02 的 href 补充
                if (categoryCode == null) {
                    Matcher m = Pattern.compile("wbtreeid=(\\d+)").matcher(catElem.attr("href"));
                    if (m.find()) categoryCode = m.group(1);
                }
            }
            // 还是 null，用 sourceConfig 的兜底
            if (categoryCode == null) categoryCode = sourceConfig.getCategoryCode();
            if (categoryName == null) categoryName = CATEGORY_MAP.getOrDefault(categoryCode, sourceConfig.getCategoryName());

            // 4. 发文单位（width03）
            String department = null;
            Element deptElem = item.selectFirst(".width03 a");
            if (deptElem != null) department = deptElem.text().trim();

            // 5. 日期（width06）
            String publishDate = null;
            Element dateElem = item.selectFirst(".width06");
            if (dateElem != null) publishDate = dateElem.text().trim();

            // 统一存绝对 URL（方案 C：废弃 detailUrlTemplate 依赖）
            String absUrl = ArticleUrlResolver.resolve(href, sourceConfig.getBaseUrl());
            if (absUrl != null && absUrl.startsWith(ArticleUrlResolver.EXTERNAL_PREFIX)) {
                absUrl = absUrl.substring(ArticleUrlResolver.EXTERNAL_PREFIX.length());
            }
            return InfoItemMeta.builder()
                    .id(id)
                    .url(absUrl != null ? absUrl : href)
                    .title(title)
                    .categoryCode(categoryCode)     // ⭐ 从 URL 提取，不再是 null
                    .categoryName(categoryName)
                    .department(department)
                    .publishDate(publishDate)
                    .source(sourceConfig.getName())
                    .channelId(sourceConfig.getChannelId())
                    .crawledAt(System.currentTimeMillis())
                    .build();
        } catch (Exception e) {
            log.warn("解析列表项失败", e);
            return null;
        }
    }

    // ==================== URL 提取 ====================

    private String extractIdFromUrl(String url) {
        Matcher m = ID_FROM_URL.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /**
     * ⭐ 从文章 URL 中提取 categoryCode
     * <p>
     * info/1020/50838.htm → "1020"
     * info/1019/50855.htm → "1019"
     */
    private String extractCategoryFromUrl(String url) {
        if (url == null) return null;
        Matcher m = CATEGORY_FROM_URL.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    // ==================== 分页解析 ====================

    private Integer parseTotalPages(Document doc) {
        // 方法1：.p_pages 链接中的 totalpage=N
        for (Element link : doc.select(".p_pages a")) {
            Matcher m = TOTALPAGE_PARAM.matcher(link.attr("href"));
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        // 方法1b：任何含 totalpage= 的链接
        for (Element link : doc.select("a[href*=totalpage]")) {
            Matcher m = TOTALPAGE_PARAM.matcher(link.attr("href"));
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        // 方法2：.p_t 中的 "共N条"
        for (Element elem : doc.select(".p_t")) {
            Matcher m = TOTAL_COUNT.matcher(elem.text());
            if (m.find()) return (Integer.parseInt(m.group(1)) + 19) / 20;
        }
        // 方法3：PAGENUM 参数最大值
        int max = 0;
        for (Element link : doc.select("a[href*=PAGENUM]")) {
            Matcher m = Pattern.compile("PAGENUM=(\\d+)").matcher(link.attr("href"));
            if (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        if (max > 1) return max;
        // 方法4：list{N}.htm
        for (Element link : doc.select("a[href*=list]")) {
            Matcher m = Pattern.compile("list(\\d+)\\.htm").matcher(link.attr("href"));
            if (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        if (max > 1) return max;
        // 方法5：JS 变量
        for (Element script : doc.select("script")) {
            Matcher m = Pattern.compile("totalPage[\\s]*[=:][\\s]*(\\d+)").matcher(script.html());
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return null;
    }

    // ==================== 错误检测 ====================

    private boolean isErrorPage(Document doc) {
        String t = doc.title().toLowerCase();
        String b = doc.body() != null ? doc.body().text().toLowerCase() : "";
        return t.contains("error") || t.contains("错误") || t.contains("404") || t.contains("403")
                || t.contains("登录") || t.contains("login") || b.contains("请登录")
                || (b.contains("cas") && b.contains("ticket"));
    }

    public static Map<String, String> getCategoryMap() { return new HashMap<>(CATEGORY_MAP); }
}