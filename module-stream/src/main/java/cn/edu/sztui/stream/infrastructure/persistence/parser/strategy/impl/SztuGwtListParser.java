package cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl;

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
 * 解析 https://gwt.sztu.edu.cn/info/{category}/list{page}.htm 页面
 */
@Slf4j
@Component
public class SztuGwtListParser implements ParserStrategy {

    /** 解析器类型标识 */
    public static final String TYPE = "sztu-gwt";

    /** 公文通基础URL */
    private static final String BASE_URL = "https://gwt.sztu.edu.cn";

    /** 分类代码 -> 分类名称映射 */
    private static final Map<String, String> CATEGORY_MAP = new HashMap<>();

    static {
        CATEGORY_MAP.put("1018", "教务");
        CATEGORY_MAP.put("1019", "科研");
        CATEGORY_MAP.put("1020", "行政");
        CATEGORY_MAP.put("1021", "学工");
        CATEGORY_MAP.put("1022", "校园");
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public ListParserResult parseList(String html, SourceConfig sourceConfig, int page) {
        if (!StringUtils.hasText(html)) {
            return ListParserResult.fail("HTML 内容为空");
        }

        try {
            Document doc = Jsoup.parse(html);

            // 检查是否是错误页面或登录页面
            if (isErrorPage(doc)) {
                return ListParserResult.fail("访问被拒绝或需要登录");
            }

            // 解析列表项
            List<InfoItemMeta> items = new ArrayList<>();
            Elements listItems = doc.select("ul.list1 li, ul.newsList li, .newslist li");

            if (listItems.isEmpty()) {
                // 尝试其他选择器
                listItems = doc.select("div.list li, table.list tr");
            }

            for (Element item : listItems) {
                InfoItemMeta meta = parseListItem(item, sourceConfig);
                if (meta != null && StringUtils.hasText(meta.getId())) {
                    items.add(meta);
                }
            }

            // 解析总页数
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
        // 列表解析器不实现详情解析
        return ContentParserResult.fail("请使用 SztuGwtContentParser 解析详情");
    }

    @Override
    public String buildListUrl(SourceConfig sourceConfig, int page) {
        String category = sourceConfig.getCategory();
        if (page == 1) {
            return BASE_URL + "/info/" + category + "/list.htm";
        }
        return BASE_URL + "/info/" + category + "/list" + page + ".htm";
    }

    /**
     * 解析单个列表项
     */
    private InfoItemMeta parseListItem(Element item, SourceConfig sourceConfig) {
        try {
            // 解析链接
            Element link = item.selectFirst("a");
            if (link == null) {
                return null;
            }

            String href = link.attr("href");
            String title = link.text().trim();

            if (!StringUtils.hasText(href) || !StringUtils.hasText(title)) {
                return null;
            }

            // 提取 ID
            String id = extractIdFromUrl(href);
            if (id == null) {
                return null;
            }

            // 解析日期
            String publishDate = null;
            Element dateElem = item.selectFirst("span.time, span.date, .time, .date");
            if (dateElem != null) {
                publishDate = dateElem.text().trim();
            } else {
                // 尝试从文本中提取日期
                publishDate = extractDateFromText(item.text());
            }

            // 解析部门
            String department = null;
            Element deptElem = item.selectFirst("span.dept, span.source, .dept, .source");
            if (deptElem != null) {
                department = deptElem.text().trim();
            }

            // 构建完整URL
            String fullUrl = href;
            if (!href.startsWith("http")) {
                if (href.startsWith("/")) {
                    fullUrl = BASE_URL + href;
                } else {
                    fullUrl = BASE_URL + "/" + href;
                }
            }

            return InfoItemMeta.builder()
                    .id(id)
                    .url(fullUrl)
                    .title(title)
                    .category(sourceConfig.getCategory())
                    .categoryName(CATEGORY_MAP.getOrDefault(sourceConfig.getCategory(), sourceConfig.getCategoryName()))
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

    /**
     * 从 URL 中提取 ID
     */
    private String extractIdFromUrl(String url) {
        // 匹配 /info/1018/50731.htm 或 50731.htm
        Pattern pattern = Pattern.compile("/(\\d+)\\.htm");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 从文本中提取日期
     */
    private String extractDateFromText(String text) {
        // 匹配 yyyy-MM-dd 或 yyyy/MM/dd 或 yyyy.MM.dd
        Pattern pattern = Pattern.compile("(\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2})");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).replace("/", "-").replace(".", "-");
        }
        return null;
    }

    /**
     * 解析总页数
     */
    private Integer parseTotalPages(Document doc) {
        // 方法1: 从分页信息文本中提取
        Element pageInfo = doc.selectFirst(".page-info, .pagination-info, span.pageinfo");
        if (pageInfo != null) {
            Pattern pattern = Pattern.compile("共\\s*(\\d+)\\s*页");
            Matcher matcher = pattern.matcher(pageInfo.text());
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        }

        // 方法2: 从分页链接中提取最大页码
        Elements pageLinks = doc.select(".pagination a, .page a, .pagelist a");
        int maxPage = 1;
        for (Element link : pageLinks) {
            String href = link.attr("href");
            Pattern pattern = Pattern.compile("list(\\d+)\\.htm");
            Matcher matcher = pattern.matcher(href);
            if (matcher.find()) {
                int pageNum = Integer.parseInt(matcher.group(1));
                maxPage = Math.max(maxPage, pageNum);
            }
        }

        // 方法3: 从 JavaScript 变量中提取
        Elements scripts = doc.select("script");
        for (Element script : scripts) {
            String scriptText = script.html();
            Pattern pattern = Pattern.compile("totalPage[\\s]*[=:][\\s]*(\\d+)");
            Matcher matcher = pattern.matcher(scriptText);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        }

        return maxPage > 1 ? maxPage : null;
    }

    /**
     * 检查是否是错误页面
     */
    private boolean isErrorPage(Document doc) {
        String title = doc.title().toLowerCase();
        String bodyText = doc.body() != null ? doc.body().text().toLowerCase() : "";

        // 检查常见的错误页面特征
        if (title.contains("error") || title.contains("错误") ||
                title.contains("404") || title.contains("403")) {
            return true;
        }

        // 检查登录页面特征
        if (title.contains("登录") || title.contains("login") ||
                bodyText.contains("请登录") || bodyText.contains("please login")) {
            return true;
        }

        // 检查 CAS 认证页面
        if (bodyText.contains("cas") && bodyText.contains("ticket")) {
            return true;
        }

        return false;
    }

    /**
     * 获取分类映射
     */
    public static Map<String, String> getCategoryMap() {
        return new HashMap<>(CATEGORY_MAP);
    }
}
