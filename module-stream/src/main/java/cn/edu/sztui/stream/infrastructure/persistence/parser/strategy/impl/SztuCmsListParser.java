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

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 深圳技术大学 CMS 通用列表解析器
 * <p>
 * 覆盖学校官网、教务部、学院子站、就业网等所有博达站群（WBS）页面。
 * <p>
 * 已验证的页面变体：
 * <ul>
 *   <li>B 型 卡片式（文娱活动 www）：li[id^=line_] > a > .jl-tx h3 + p</li>
 *   <li>C 型 图文混排（体育活动 www）：a.list_vtc > .ty-tx h3 + span</li>
 *   <li>D 型 无图有序号（人力资源部 hr）：li > a.flex-box > .text span + em</li>
 *   <li>E 型 极简式（工程物理学院）：li > a[title] + span.date</li>
 *   <li>F 型 content.jsp（国有资产部）：li > a[href*=content.jsp] > p.bt + p.p1</li>
 *   <li>G 型 就业网（jyzd）：li.item > a.item-link[title] + span.item-time</li>
 *   <li>H 型 杂志卡片（教务部教学动态 jw）：div.soga11 > p.soga_p > a.soga_a + span.timer1</li>
 * </ul>
 * <p>
 * 外链处理策略：
 * <ul>
 *   <li>info/ 开头 → 站内文章，正常爬详情</li>
 *   <li>mp.weixin.qq.com → 微信外链，保留元数据，ID 前缀 wx_</li>
 *   <li>其他外部 URL（政府网站/媒体等）→ 保留元数据，ID 前缀 ext_</li>
 *   <li>bysjy.com.cn（招聘平台导航）→ 过滤掉</li>
 * </ul>
 */
@Slf4j
@Component
public class SztuCmsListParser implements ParserStrategy {

    public static final String TYPE = "sztu-cms";

    /**
     * 日期正则
     */
    private static final Pattern DATE_PATTERN =
            Pattern.compile("(\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2})");

    /**
     * info/{category}/{id}.htm
     */
    private static final Pattern INFO_URL_PATTERN =
            Pattern.compile("info/(\\d+)/(\\d+)\\.htm");

    /**
     * content.jsp?wbnewsid=xxx
     */
    private static final Pattern CONTENT_JSP_PATTERN =
            Pattern.compile("wbnewsid=(\\d+)");

    /**
     * 末尾数字 ID：/1234.htm
     */
    private static final Pattern SIMPLE_ID_PATTERN =
            Pattern.compile("/(\\d+)\\.htm");

    /**
     * 分页："共N条"
     */
    private static final Pattern TOTAL_COUNT_PATTERN =
            Pattern.compile("共\\s*(\\d+)\\s*条");

    /**
     * 分页：路径 /{N}.htm
     */
    private static final Pattern PATH_PAGE_PATTERN =
            Pattern.compile("/(\\d+)\\.htm");

    /**
     * 分页：a1070532p={N} 就业网分页参数
     */
    private static final Pattern JYZD_PAGE_PATTERN =
            Pattern.compile("a\\d+p=(\\d+)");

    /**
     * 导航页面的路径关键词，这些不是文章
     */
    private static final Set<String> NAV_KEYWORDS = Set.of(
            "index", "list", "xxgk", "rcpy", "kysx", "dwjl", "zsjy",
            "szll", "hljd", "ksrk", "xxjj", "bmgk", "xydt", "xyfg",
            "xxxl", "xqhz", "kxyj", "gjjl", "xsjwjl", "fwc", "ywbl"
    );

    /**
     * 明确的导航/平台域名，不是文章
     */
    private static final Set<String> NAV_DOMAINS = Set.of(
            "bysjy.com.cn",       // 就业平台
            "brandpano.com",      // VR 校园
            "v2.brandpano.com"
    );

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
            if (isErrorPage(doc)) {
                return ListParserResult.fail("访问被拒绝或需要登录");
            }

            String baseUrl = sourceConfig.getBaseUrl();
            List<InfoItemMeta> items = extractItems(doc, sourceConfig, baseUrl);
            Integer totalPages = parseTotalPages(doc);

            log.debug("CMS 列表解析 - 源: {}, 页码: {}, 条目: {}, 总页数: {}",
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
            log.error("CMS 列表解析失败 - 源: {}, 页码: {}", sourceConfig.getId(), page, e);
            return ListParserResult.fail("解析失败: " + e.getMessage());
        }
    }

    @Override
    public ContentParserResult parseContent(String html, SourceConfig sourceConfig, String itemId) {
        return ContentParserResult.fail("请使用 SztuGwtContentParser 解析详情");
    }

    // ==================== 核心：提取列表项 ====================

    private List<InfoItemMeta> extractItems(Document doc, SourceConfig sourceConfig, String baseUrl) {
        List<InfoItemMeta> items = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        // 匹配所有可能包含文章链接的容器：
        // 1. li a[href] — 标准 CMS 列表
        // 2. div.soga11 a[href] — H型布局
        // 3. div.item a[href] — 中德学院等图片卡片布局
        // 4. div.havePictureList_list > a[href] — 人工智能学院等图片列表布局
        // 5. tr a[href] — 学工部等 TABLE 布局（老版 CMS）
        Elements allAnchors = doc.select(
                "li a[href], div.soga11 a[href], div.item a[href], " +
                "div.havePictureList_list > a[href], tr a[href], " +
                "div.content-list a[href], div.list a[href]"
        );

        for (Element anchor : allAnchors) {
            String href = anchor.attr("href").trim();

            // 第一层过滤：排除明显的非文章链接
            if (!isArticleCandidate(href)) continue;

            // 第二层过滤：必须有实质内容（标题或 title 属性）
            String title = extractTitle(anchor);
            if (!StringUtils.hasText(title)) continue;

            // 解析 URL 和外链状态
            boolean isExternal = isExternalUrl(href, baseUrl);
            String fullUrl;
            if (href.startsWith("http")) {
                fullUrl = href;
            } else {
                fullUrl = ArticleUrlResolver.resolve(href, baseUrl);
                if (fullUrl != null && fullUrl.startsWith(ArticleUrlResolver.EXTERNAL_PREFIX)) {
                    fullUrl = fullUrl.substring(ArticleUrlResolver.EXTERNAL_PREFIX.length());
                    isExternal = true;
                }
            }
            if (fullUrl == null) continue;

            // 提取 ID
            String id = extractId(href, fullUrl, isExternal);
            if (id == null || id.isEmpty()) continue;

            // 去重
            if (seenIds.contains(id)) continue;
            seenIds.add(id);

            // 提取分类码（从 URL）
            String categoryCode = ArticleUrlResolver.extractCategory(fullUrl);
            if (categoryCode == null) {
                categoryCode = ArticleUrlResolver.extractCategory(href);
            }

            // 提取日期
            String publishDate = extractDate(anchor);

            InfoItemMeta meta = InfoItemMeta.builder()
                    .id(id)
                    .url(isExternal ? fullUrl : href)
                    .title(title.trim())
                    .categoryCode(categoryCode != null ? categoryCode : sourceConfig.getCategoryCode())
                    .categoryName(sourceConfig.getCategoryName())
                    .publishDate(publishDate)
                    .source(sourceConfig.getName())
                    .channelId(sourceConfig.getChannelId())
                    .crawledAt(System.currentTimeMillis())
                    .extra(isExternal ? "{\"external\":true}" : null)
                    .build();

            items.add(meta);
        }

        return items;
    }

    // ==================== 链接过滤（两层） ====================

    /**
     * 第一层：粗过滤——排除绝不可能是文章的链接
     * <p>
     * 宽松策略：只排除明确的导航/功能链接，其他全部放行到第二层（标题检测）
     */
    private boolean isArticleCandidate(String href) {
        if (!StringUtils.hasText(href)) return false;
        if (href.startsWith("#") || href.startsWith("javascript:")) return false;
        if (href.contains("mailto:") || href.contains("tel:")) return false;

        // ⭐ 排除导航平台域名（bysjy 招聘平台、VR 校园等）
        for (String domain : NAV_DOMAINS) {
            if (href.contains(domain)) return false;
        }

        // 排除明确的导航页面路径（如 index.htm, xxgk.htm）
        // 但只排除"纯导航路径"，如果 href 含 info/ 或查询参数则保留
        if (href.contains("info/") || href.contains("content.jsp")) return true;
        if (href.contains("mp.weixin.qq.com")) return true;
        if (href.startsWith("http")) return true; // ⭐ 所有外部 http 链接放行到第二层

        // 校内相对路径：检查是否是导航页
        if (href.endsWith(".htm") && !href.contains("?")) {
            String filename = href.contains("/") ? href.substring(href.lastIndexOf('/') + 1) : href;
            String stem = filename.replace(".htm", "");
            // 纯导航关键词
            if (NAV_KEYWORDS.contains(stem.toLowerCase())) return false;
            // 包含数字 ID 的 .htm → 大概率是文章
            if (SIMPLE_ID_PATTERN.matcher(href).find()) return true;
            // 不含数字的短路径 → 大概率是导航
            if (!stem.matches(".*\\d+.*") && stem.length() < 15) return false;
        }

        return true;
    }

    /**
     * 判断是否为外部链接（非 sztu.edu.cn 域名）
     */
    private boolean isExternalUrl(String href, String baseUrl) {
        if (!href.startsWith("http")) return false;
        return !href.contains("sztu.edu.cn");
    }

    // ==================== 标题提取 ====================

    private String extractTitle(Element anchor) {
        // 1. title 属性（最可靠：就业网/人力资源部/国有资产部都有）
        String titleAttr = anchor.attr("title");
        if (StringUtils.hasText(titleAttr) && titleAttr.length() > 3) {
            return titleAttr.trim();
        }

        // 2. a 内的 h3
        Element h3 = anchor.selectFirst("h3");
        if (h3 != null && StringUtils.hasText(h3.text())) {
            return h3.text().trim();
        }

        // 3. 特定 class 元素（各学院/部门模板用不同 class 名）
        for (String sel : new String[]{".bt", ".title", ".text span", ".con p", "p.bt", ".soga_a",
                                       ".info_plate .name", ".info_plate h4", ".news-title"}) {
            Element el = anchor.selectFirst(sel);
            if (el != null && StringUtils.hasText(el.text()) && !isDateString(el.text())) {
                return el.text().trim();
            }
        }

        // 4. a 内第一个有意义的文本子节点
        for (Element child : anchor.select("p, span, div")) {
            String text = child.ownText().trim();
            if (StringUtils.hasText(text) && !isDateString(text)
                    && text.length() > 4 && !text.equals("查看详情")) {
                return text;
            }
        }

        // 5. a 的完整文本去掉日期和"查看详情"等噪声
        String fullText = anchor.text().trim();
        if (StringUtils.hasText(fullText)) {
            String cleaned = DATE_PATTERN.matcher(fullText).replaceAll("").trim();
            cleaned = cleaned.replace("查看详情", "").trim();
            // 去掉序号前缀（如 "1 标题" → "标题"）
            cleaned = cleaned.replaceFirst("^\\d+\\s*", "");
            if (StringUtils.hasText(cleaned) && cleaned.length() > 2) {
                return cleaned;
            }
        }

        return null;
    }

    // ==================== 日期提取 ====================

    private String extractDate(Element anchor) {
        // 先在 a 内找
        String dateInAnchor = findDateInElement(anchor);
        if (dateInAnchor != null) return dateInAnchor;

        // 在 a 的各种父容器中找日期
        // li（标准列表）
        Element parentLi = anchor.closest("li");
        if (parentLi != null) {
            for (Element el : parentLi.select("span.item-time, em, span.date, .sj p, time")) {
                String date = findDateInText(el.text());
                if (date != null) return date;
            }
            return findDateInText(parentLi.text());
        }

        // div.soga11（H 型布局）
        Element parentSoga = anchor.closest("div.soga11");
        if (parentSoga != null) {
            Element timer = parentSoga.selectFirst("span.timer1");
            if (timer != null) {
                String date = findDateInText(timer.text());
                if (date != null) return date;
            }
            return findDateInText(parentSoga.text());
        }

        // div.item（图片卡片布局 - 中德学院等）
        Element parentItem = anchor.closest("div.item");
        if (parentItem != null) {
            return findDateInText(parentItem.text());
        }

        // tr（TABLE 布局 - 学工部等）
        Element parentTr = anchor.closest("tr");
        if (parentTr != null) {
            return findDateInText(parentTr.text());
        }

        return null;
    }

    private String findDateInElement(Element element) {
        for (String sel : new String[]{
                "span.date", "span.item-time", "em", ".sj p", ".sj", "time",
                ".jl-tx p", ".ty-tx span", "p.p1", ".yy-ifo > span"}) {
            Element dateEl = element.selectFirst(sel);
            if (dateEl != null) {
                String date = findDateInText(dateEl.text());
                if (date != null) return date;
            }
        }
        return findDateInText(element.text());
    }

    private String findDateInText(String text) {
        if (text == null) return null;
        Matcher m = DATE_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1).replace("/", "-").replace(".", "-");
        }
        return null;
    }

    // ==================== ID 提取 ====================

    /**
     * 从 URL 提取 ID
     * <p>
     * 策略：
     * - info/{cat}/{id}.htm → 数字 id
     * - content.jsp?wbnewsid=xxx → 数字 id
     * - mp.weixin.qq.com/s/xxx → "wx_" + xxx
     * - 其他外链 → "ext_" + hashCode（保证唯一且稳定）
     * - 末尾 /1234.htm → 数字 id
     */
    private String extractId(String originalHref, String resolvedUrl, boolean isExternal) {
        // 1. info/{cat}/{id}.htm
        Matcher m = INFO_URL_PATTERN.matcher(resolvedUrl);
        if (m.find()) return m.group(2);
        m = INFO_URL_PATTERN.matcher(originalHref);
        if (m.find()) return m.group(2);

        // 2. content.jsp?wbnewsid=xxx
        m = CONTENT_JSP_PATTERN.matcher(resolvedUrl);
        if (m.find()) return m.group(1);
        m = CONTENT_JSP_PATTERN.matcher(originalHref);
        if (m.find()) return m.group(1);

        // 3. 微信公众号
        if (resolvedUrl.contains("mp.weixin.qq.com")) {
            int lastSlash = resolvedUrl.lastIndexOf('/');
            if (lastSlash > 0 && lastSlash < resolvedUrl.length() - 1) {
                String seg = resolvedUrl.substring(lastSlash + 1);
                int q = seg.indexOf('?');
                if (q > 0) seg = seg.substring(0, q);
                if (StringUtils.hasText(seg)) return "wx_" + seg;
            }
            // fallback：用 URL hash
            return "wx_" + Math.abs(resolvedUrl.hashCode());
        }

        // 4. 其他外链（政府网站、媒体等）
        if (isExternal) {
            // 用 URL 的 stable hash 作为 ID，保证同一 URL 始终生成同一 ID
            return "ext_" + Math.abs(resolvedUrl.hashCode());
        }

        // 5. 末尾数字 ID：/1234.htm
        m = SIMPLE_ID_PATTERN.matcher(originalHref);
        if (m.find()) return m.group(1);

        return null;
    }

    // ==================== 分页解析 ====================

    private Integer parseTotalPages(Document doc) {
        // 方法 1：.p_t "共N条"
        for (Element el : doc.select(".p_t")) {
            Matcher m = TOTAL_COUNT_PATTERN.matcher(el.text());
            if (m.find()) {
                int totalCount = Integer.parseInt(m.group(1));
                int pageSize = estimatePageSize(doc);
                int totalPages = (totalCount + pageSize - 1) / pageSize;
                log.debug("CMS 分页：{} 条 / {} 每页 = {} 页", totalCount, pageSize, totalPages);
                return totalPages;
            }
        }

        // 方法 2：.p_pages 中的最大页码
        int maxPage = 1;
        for (Element link : doc.select(".p_pages a, .p_pages span.p_no a")) {
            String href = link.attr("href");
            // 路径分页：/8.htm
            Matcher pathM = PATH_PAGE_PATTERN.matcher(href);
            if (pathM.find()) {
                maxPage = Math.max(maxPage, Integer.parseInt(pathM.group(1)));
            }
            // 就业网分页：a1070532p=2
            Matcher jyzdM = JYZD_PAGE_PATTERN.matcher(href);
            if (jyzdM.find()) {
                maxPage = Math.max(maxPage, Integer.parseInt(jyzdM.group(1)));
            }
            // 文本页码
            String text = link.text().trim();
            if (text.matches("\\d+")) {
                maxPage = Math.max(maxPage, Integer.parseInt(text));
            }
        }

        // 方法 3：.p_no / .p_no_d span 中的页码
        for (Element span : doc.select(".p_pages span.p_no, .p_pages span.p_no_d")) {
            String text = span.text().trim();
            if (text.matches("\\d+")) {
                maxPage = Math.max(maxPage, Integer.parseInt(text));
            }
        }

        // 方法 4：尾页链接
        Element lastPage = doc.selectFirst(".p_last a");
        if (lastPage != null) {
            String href = lastPage.attr("href");
            Matcher m = JYZD_PAGE_PATTERN.matcher(href);
            if (m.find()) {
                maxPage = Math.max(maxPage, Integer.parseInt(m.group(1)));
            }
            m = PATH_PAGE_PATTERN.matcher(href);
            if (m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (n == 1 && maxPage > 1) {
                    // 降序分页：尾页是 /1.htm
                    return maxPage;
                }
                maxPage = Math.max(maxPage, n);
            }
        }

        if (maxPage > 1) {
            log.debug("CMS 分页：从链接推算 {} 页", maxPage);
            return maxPage;
        }

        // 方法 5：JS 变量
        for (Element script : doc.select("script")) {
            Matcher m = Pattern.compile("totalPage\\s*[=:]\\s*(\\d+)").matcher(script.html());
            if (m.find()) return Integer.parseInt(m.group(1));
        }

        return null;
    }

    private int estimatePageSize(Document doc) {
        int count = 0;
        for (Element a : doc.select("li a[href]")) {
            if (isArticleCandidate(a.attr("href"))) count++;
        }
        if (count > 0 && count <= 30) return count;
        return 15;
    }

    // ==================== 工具方法 ====================

    private boolean isDateString(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.length() <= 12 && DATE_PATTERN.matcher(t).find();
    }

    private boolean isErrorPage(Document doc) {
        String title = doc.title().toLowerCase();
        String body = doc.body() != null ? doc.body().text().toLowerCase() : "";
        return title.contains("error") || title.contains("错误")
                || title.contains("404") || title.contains("403")
                || title.contains("登录") || title.contains("login")
                || body.contains("请登录");
    }
}