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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 教务内网（jsxsd / 强智教务）的列表 + 详情解析器。
 * <p>
 * 覆盖三个入口：
 * <ul>
 *   <li>/ggly/ysgg_query - 已收公告（公告留言类）</li>
 *   <li>/ggly/ysly_query - 已收留言（公告留言类）</li>
 *   <li>/ggly/xxtz_query - 消息通知（无详情页）</li>
 * </ul>
 * <p>
 * <b>WebVPN 编码踩坑</b>：强智教务系统通过 WebVPN 网关后，HTML 里的 inline style 的
 * 双引号 <code>"</code> 会被网关替换成 <code>&s380</code>（似乎是字符 0x26 + s380）。
 * 解析前统一做一次字符级修复，避免 Jsoup 解析挂。
 */
@Slf4j
@Component
public class AcdmInboxParser implements ParserStrategy {

    public static final String TYPE = "acdm-inbox";

    /**
     * ID 提取：强智教务的详情链接通常带 <code>ggid=XXX</code> 或 onclick 里的 JavaScript
     * 调用（如 <code>showGg('20250316001')</code>）。都尝试。
     */
    private static final Pattern ID_FROM_QUERY = Pattern.compile("[?&]ggid=([^&'\\\"\\s]+)", Pattern.CASE_INSENSITIVE);
    /** 匹配任意 JS 函数调用里的第一个字符串实参，如 showGg('xxx') / readMsg("xxx") */
    private static final Pattern ID_FROM_JS = Pattern.compile("\\w+\\(\\s*['\\\"]([^'\\\"]+)['\\\"]");

    @Override
    public String getType() { return TYPE; }

    // ==================== 列表 ====================

    /**
     * 列表页解析。jsxsd 三个入口都把数据放在 {@code <table id="dataList">} 里，
     * 但**列数不同**，按列数分派：
     * <ul>
     *   <li>7 列 = 已收公告 / 已收留言：序号 / 标题 / 状态 / 类别 / 发送人 / 发送时间 /
     *       操作（"查看"链接含 <code>openWindow('...?ggid=XXX')</code>）</li>
     *   <li>4 列 = 消息通知：序号 / 业务名称 / 消息内容 / 推送时间（没有 id，合成一个）</li>
     * </ul>
     */
    @Override
    public ListParserResult parseList(String html, SourceConfig sourceConfig, int page) {
        if (!StringUtils.hasText(html)) return ListParserResult.fail("HTML 内容为空");

        String fixed = fixWebVpnEncoding(html);

        // 认证态判断：jsxsd session 过期后会返回登录页（内容没有列表结构）。
        // 调度器据此触发自愈（reactive initInternal），避免把"登录页 0 条"误作"当前无公告"。
        if (isJsxsdLoginPage(fixed)) {
            ListParserResult result = new ListParserResult();
            result.setSuccess(false);
            result.setAuthExpired(true);
            result.setErrorMessage("jsxsd 登录页响应");
            return result;
        }

        Document doc = Jsoup.parse(fixed);
        Elements rows = doc.select("table#dataList tr");

        List<InfoItemMeta> items = new ArrayList<>();
        for (Element row : rows) {
            Elements tds = row.children();
            // 跳过表头（tr 只有 <th>），以及任何只有 0-1 个 cell 的异常行
            if (tds.isEmpty()) continue;
            boolean anyTd = false;
            for (Element c : tds) {
                if ("td".equalsIgnoreCase(c.tagName())) { anyTd = true; break; }
            }
            if (!anyTd) continue;

            InfoItemMeta meta = parseRow(tds);
            if (meta != null) items.add(meta);
        }

        ListParserResult result = new ListParserResult();
        result.setSuccess(true);
        result.setItems(items);
        result.setCurrentPage(page);
        return result;
    }

    /** 根据 td 数量分派到 ggly（7 列）或 xxtz（4 列）解析器 */
    private InfoItemMeta parseRow(Elements tds) {
        // 只取 td，不含可能混入的空白节点
        List<Element> cells = new ArrayList<>();
        for (Element e : tds) if ("td".equalsIgnoreCase(e.tagName())) cells.add(e);

        if (cells.size() >= 6) return parseGglyRow(cells);
        if (cells.size() == 4) return parseXxtzRow(cells);
        return null;
    }

    /**
     * 已收公告 / 已收留言行解析。
     * 列：[0]序号 [1]标题 [2]状态(已读/未读) [3]类别 [4]发送人 [5]发送时间 [6]操作(&lt;a&gt;)
     */
    private InfoItemMeta parseGglyRow(List<Element> cells) {
        String title = cellText(cells, 1);
        if (!StringUtils.hasText(title)) return null;

        String status = cellText(cells, 2);
        String category = cellText(cells, 3);
        String author = cellText(cells, 4);
        String publishTime = cellText(cells, 5);

        // 最后一个 td 里的 a[href=javascript:openWindow('...?ggid=XXX',...)] 抠 ggid
        String id = "";
        Element last = cells.get(cells.size() - 1);
        Element a = last.selectFirst("a[href]");
        if (a != null) {
            id = extractId(a.attr("href"), a.attr("onclick"));
        }
        if (!StringUtils.hasText(id)) return null; // 没 id 丢弃

        return InfoItemMeta.builder()
                .id(id)
                .title(title)
                .author(author)
                .publishDate(publishTime)
                .categoryName(category)
                .summary(status) // 借 summary 字段透出 已读/未读
                .build();
    }

    /**
     * 消息通知行解析（无详情页）。
     * 列：[0]序号 [1]业务名称 [2]消息内容 [3]推送时间 —— 学校没给 id，合成一个稳定 hash
     */
    private InfoItemMeta parseXxtzRow(List<Element> cells) {
        String bizName = cellText(cells, 1);
        String content = cellText(cells, 2);
        String publishTime = cellText(cells, 3);

        String title = StringUtils.hasText(bizName) && StringUtils.hasText(content)
                ? bizName + "：" + content
                : (StringUtils.hasText(content) ? content : bizName);
        if (!StringUtils.hasText(title)) return null;

        String id = synthesizeXxtzId(bizName, content, publishTime);

        return InfoItemMeta.builder()
                .id(id)
                .title(title)
                .publishDate(publishTime)
                .categoryName(bizName)
                .build();
    }

    // ==================== 详情 ====================

    /**
     * 详情页解析。结构固定：一个顶部 <td> 是标题（font-size:14pt 的 td），
     * 下方 <td> 是正文（嵌套在 font 标签里），再下方 <td> 是"发布类别/发布人/发布时间/有效期"的元信息。
     * 消息通知（xxtz_query）没有详情页，走这里会拿不到内容——返回空 content 但仍标 success。
     */
    @Override
    public ContentParserResult parseContent(String html, SourceConfig sourceConfig, String itemId) {
        if (!StringUtils.hasText(html)) return ContentParserResult.fail("HTML 内容为空");

        String fixed = fixWebVpnEncoding(html);
        Document doc = Jsoup.parse(fixed);

        // 标题：font-size:14pt 的 td 是"公告标题"；如未命中则尝试 toolstitle 里的 font
        String title = firstNonEmpty(
                textOfFirst(doc, "td[style*=14pt]"),
                textOfFirst(doc, ".toolstitle font"),
                doc.title()
        );
        title = cleanTitle(title);

        // 正文：title 下一个 td 的 font 内容（原始 HTML）
        String content = innerHtmlOfFirst(doc, "td[style*=F3E2E2] font",
                "td[style*=FAFAFA] font",
                "table td font");

        // 作者 / 发布时间：从"发布人：XXX" / "发布时间：YYYY-MM-DD HH:mm:ss" 里正则捞
        String metaText = doc.body() == null ? "" : doc.body().text();
        String author = regexGroup(metaText, "发\\s*布\\s*人[：:]\\s*([^\\s发]+)");
        String publishTime = regexGroup(metaText, "发\\s*布\\s*时\\s*间[：:]\\s*([0-9\\-: ]+)");

        return ContentParserResult.builder()
                .success(true)
                .id(itemId)
                .title(title)
                .author(author)
                .publishTime(publishTime)
                .content(content == null ? "" : content)
                .build();
    }

    // ==================== 辅助（package-private 便于测试） ====================

    /** WebVPN 网关把 inline style 的 " 替换成 &s380，统一还原 */
    static String fixWebVpnEncoding(String html) {
        if (html == null) return "";
        return html.replace("&s380", "\"");
    }

    /**
     * 判断 jsxsd 响应是否为登录页。
     * <p>
     * jsxsd session 过期后，原本的列表端点（如 /ggly/ysgg_query）会被服务端 302/forward 到登录页，
     * body 里会出现典型的登录 form 标记。任一命中即判定。
     * <ul>
     *   <li><code>/jsxsd/xk/LoginToXk</code> / <code>loginHome</code> 路径</li>
     *   <li><code>id="userAccount"</code> 登录表单输入</li>
     *   <li>title 含"登录"</li>
     * </ul>
     */
    static boolean isJsxsdLoginPage(String html) {
        if (html == null || html.isEmpty()) return false;
        String lower = html.toLowerCase();
        if (lower.contains("/jsxsd/xk/logintoxk")) return true;
        if (lower.contains("loginhome")) return true;
        if (lower.contains("id=\"useraccount\"") || lower.contains("id='useraccount'")) return true;
        if (lower.contains("name=\"useraccount\"") || lower.contains("name='useraccount'")) return true;
        // <title>登录</title> / <title>强智科技 - 教务管理系统登录</title>
        if (lower.matches("(?s).*<title[^>]*>[^<]*登录[^<]*</title>.*")) return true;
        return false;
    }

    /** 安全取第 idx 个 cell 的 text（越界返回空） */
    static String cellText(List<Element> cells, int idx) {
        if (idx < 0 || idx >= cells.size()) return "";
        Element e = cells.get(idx);
        return e == null ? "" : e.text().trim();
    }

    /**
     * 消息通知无 id，用 (业务名称 + 消息内容 + 推送时间) 的 MD5 合成稳定 id。
     * 同一条 xxtz 每次爬取都会生成相同 id，天然支持去重。
     */
    static String synthesizeXxtzId(String bizName, String content, String publishTime) {
        String raw = (bizName == null ? "" : bizName) + "|"
                + (content == null ? "" : content) + "|"
                + (publishTime == null ? "" : publishTime);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return "xxtz-" + sb.substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            return "xxtz-" + Integer.toHexString(raw.hashCode());
        }
    }

    static String extractId(String href, String onclick) {
        Matcher m = ID_FROM_QUERY.matcher(href == null ? "" : href);
        if (m.find()) return m.group(1);
        m = ID_FROM_JS.matcher(onclick == null ? "" : onclick);
        if (m.find()) return m.group(1);
        // 最后兜底：href 最后一段（例如 /ggly/gglyShow.do?ggid=xxx）
        if (href != null && href.contains("ggid=")) {
            int idx = href.indexOf("ggid=");
            String tail = href.substring(idx + 5);
            int end = tail.indexOf('&');
            return end > 0 ? tail.substring(0, end) : tail;
        }
        return "";
    }

    static boolean isDateLike(String s) {
        if (s == null) return false;
        return s.matches(".*\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*");
    }

    static String regexGroup(String text, String regex) {
        if (text == null) return "";
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    static String textOfFirst(Document doc, String selector) {
        Element el = doc.selectFirst(selector);
        return el == null ? "" : el.text().trim();
    }

    static String innerHtmlOfFirst(Document doc, String... selectors) {
        for (String s : selectors) {
            Element el = doc.selectFirst(s);
            if (el != null) {
                String html = el.html();
                if (StringUtils.hasText(html)) return html;
            }
        }
        return "";
    }

    static String firstNonEmpty(String... candidates) {
        for (String c : candidates) if (StringUtils.hasText(c)) return c;
        return "";
    }

    static String cleanTitle(String t) {
        if (t == null) return "";
        return t.replaceAll("^(公告留言|消息通知)\\s*[\\-–—:：]?\\s*", "").trim();
    }

    private String resolveUrl(SourceConfig source, String relative, String id) {
        if (relative == null) return "";
        if (relative.startsWith("http")) return relative;
        String base = source.getBaseUrl() == null ? "" : source.getBaseUrl();
        if (base.endsWith("/") && relative.startsWith("/")) base = base.substring(0, base.length() - 1);
        return base + (relative.startsWith("/") ? "" : "/") + relative;
    }

    @Override
    public boolean requiresAuth() { return true; }
}
