package cn.edu.sztui.stream.application.external.engine;

import cn.edu.sztui.common.util.smarthttp.dto.SmartResponse;
import cn.edu.sztui.common.util.smarthttp.service.SmartHttpClient;
import cn.edu.sztui.common.util.smarthttp.service.SmartSession;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfigLoader;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ContentParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ParserFactory;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import cn.edu.sztui.stream.infrastructure.util.stream.StreamKeys;
import cn.edu.sztui.stream.infrastructure.util.stream.StreamPublisher;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 通用爬取引擎
 * <p>
 * ⭐ 本次改动：buildListUrl() 增加 listUrl 支持（CMS 页面用固定 URL + 路径分页）
 */
@Slf4j
@Service
public class CrawlEngine {

    @Resource
    private SmartHttpClient smartHttpClient;

    @Resource
    private CrawlerConfigLoader configLoader;

    @Resource
    private ParserFactory parserFactory;

    @Resource
    private InfoCacheUtil infoCacheUtil;

    @Resource
    private CookieSourceManager cookieSourceManager;

    @Resource
    private StreamPublisher streamPublisher;

    private final ExecutorService initExecutor = Executors.newFixedThreadPool(10);

    // ==================== 增量爬取 ====================

    public CrawlResult crawlIncremental(String sourceId) {
        CrawlerConfig.SourceConfig source = configLoader.getSource(sourceId);
        if (source == null) {
            return CrawlResult.fail(sourceId, "未找到数据源配置: " + sourceId);
        }

        try {
            SmartSession session = resolveSession(source);

            String listUrl = buildListUrl(source, 1);
            SmartResponse response = smartHttpClient.get(listUrl, session);
            if (!response.isSuccess()) {
                return CrawlResult.fail(sourceId, "HTTP 请求失败: " + response.getStatusCode());
            }

            ListParserResult result = parserFactory.parseList(
                    source.getParserType(), response.getBody(), source, 1);

            if (result == null || result.getItems() == null || result.getItems().isEmpty()) {
                return CrawlResult.empty(sourceId);
            }

            String channelId = source.getChannelId();
            String cachedLatestId = infoCacheUtil.getLatestId(channelId);
            List<ListParserResult.InfoItemMeta> newItems = filterNewItems(result.getItems(), cachedLatestId);

            if (newItems.isEmpty()) {
                infoCacheUtil.updateLastCrawlTime(sourceId);
                return CrawlResult.empty(sourceId);
            }

            infoCacheUtil.saveMetaBatch(channelId, newItems);

            String newLatestId = newItems.stream()
                    .map(ListParserResult.InfoItemMeta::getId)
                    .map(Long::parseLong)
                    .max(Long::compareTo)
                    .map(String::valueOf)
                    .orElse(cachedLatestId);
            infoCacheUtil.setLatestId(channelId, newLatestId);
            infoCacheUtil.updateLastCrawlTime(sourceId);

            broadcastNewContent(channelId, newItems, newLatestId);

            List<String> ids = newItems.stream().map(ListParserResult.InfoItemMeta::getId).toList();
            log.info("增量爬取完成: source={}, 新增 {} 条", sourceId, newItems.size());
            return CrawlResult.success(sourceId, newItems.size(), ids, newLatestId);

        } catch (CookieSourceManager.NoCookieAvailableException e) {
            log.debug("增量爬取跳过（无 Cookie）: source={}", sourceId);
            return CrawlResult.fail(sourceId, e.getMessage());
        } catch (Exception e) {
            log.error("增量爬取失败: source={}, error={}", sourceId, e.getMessage(), e);
            return CrawlResult.fail(sourceId, e.getMessage());
        }
    }

    // ==================== 全量初始化 ====================

    public void initSource(String sourceId, String openId) {
        CrawlerConfig.SourceConfig source = configLoader.getSource(sourceId);
        if (source == null) {
            log.error("初始化失败，未找到数据源: {}", sourceId);
            return;
        }

        String channelId = source.getChannelId();

        if (infoCacheUtil.isSourceInitialized(sourceId)) {
            log.info("数据源已初始化，跳过: {}", sourceId);
            return;
        }

        long startTime = System.currentTimeMillis();
        log.info("======== 开始初始化数据源: {} ({}) ========", source.getName(), sourceId);

        try {
            SmartSession session = resolveSession(source);

            String firstPageUrl = buildListUrl(source, 1);
            SmartResponse firstResponse = smartHttpClient.get(firstPageUrl, session);
            if (!firstResponse.isSuccess()) {
                log.error("获取首页失败: source={}, status={}", sourceId, firstResponse.getStatusCode());
                return;
            }

            ListParserResult firstResult = parserFactory.parseList(
                    source.getParserType(), firstResponse.getBody(), source, 1);

            int totalPage = (firstResult != null && firstResult.getTotalPages() != null)
                    ? firstResult.getTotalPages() : 1;
            if (totalPage <= 0) totalPage = 1;

            int maxPages = source.getPageCount() > 0 ? source.getPageCount() : totalPage;
            int pagesToCrawl = Math.min(totalPage, maxPages);
            log.info("数据源 {} 总页数: {}, 将爬取: {} 页", sourceId, totalPage, pagesToCrawl);

            List<ListParserResult.InfoItemMeta> allItems = new ArrayList<>();
            if (firstResult != null && firstResult.getItems() != null) {
                allItems.addAll(firstResult.getItems());
            }

            if (pagesToCrawl > 1) {
                allItems.addAll(crawlPages(source, session, 2, pagesToCrawl));
            }

            if (allItems.isEmpty()) {
                log.warn("初始化无结果: {}", sourceId);
                return;
            }

            infoCacheUtil.saveMetaBatch(channelId, allItems);

            String latestId = allItems.stream()
                    .map(m -> {
                        try {
                            return Long.parseLong(m.getId());
                        } catch (NumberFormatException e) {
                            return 0L;
                        }
                    })
                    .max(Long::compareTo)
                    .map(String::valueOf)
                    .orElse("0");
            infoCacheUtil.setLatestId(channelId, latestId);
            infoCacheUtil.markSourceInitialized(sourceId);

            long duration = (System.currentTimeMillis() - startTime) / 1000;
            log.info("======== 初始化完成: {} - {} 条, 耗时 {}s ========", sourceId, allItems.size(), duration);

        } catch (Exception e) {
            log.error("初始化失败: source={}, error={}", sourceId, e.getMessage(), e);
        }
    }

    // ==================== 详情爬取 ====================

    public ContentParserResult crawlDetail(String sourceId, String id, String categoryCode) {
        CrawlerConfig.SourceConfig source = configLoader.getSource(sourceId);
        if (source == null) return null;

        try {
            SmartSession session = resolveSession(source);
            String detailUrl = buildDetailUrl(source, id, categoryCode);

            SmartResponse response = smartHttpClient.get(detailUrl, session);
            if (!response.isSuccess()) {
                log.error("爬取详情失败: source={}, id={}, status={}", sourceId, id, response.getStatusCode());
                return null;
            }

            ContentParserResult content = parserFactory.parseContent(
                    source.getParserType(), response.getBody(), source, id);
            if (content != null) {
                content.setId(id);
            }
            return content;

        } catch (Exception e) {
            log.error("爬取详情异常: source={}, id={}, error={}", sourceId, id, e.getMessage());
            return null;
        }
    }

    // ==================== 内部方法 ====================

    private SmartSession resolveSession(CrawlerConfig.SourceConfig source) {
        if (source.isRequiresAuth()) {
            return cookieSourceManager.getAvailableSession();
        }
        return smartHttpClient.newSession();
    }

    /**
     * 构建列表页 URL
     * <p>
     * ⭐ 支持两种模式：
     * <p>
     * 模式 1（listUrl）：CMS 页面的固定 URL + 路径分页
     * 第1页：https://www.sztu.edu.cn/hljd/xyhd/wyhd.htm（原始 URL）
     * 第2页：https://www.sztu.edu.cn/hljd/xyhd/wyhd/2.htm
     * 第3页：https://www.sztu.edu.cn/hljd/xyhd/wyhd/3.htm
     * （博达 CMS 标准分页格式：去掉 .htm 后缀，加 /{page}.htm）
     * <p>
     * 模式 2（listUrlTemplate）：公文通 list.jsp 的模板 URL
     * https://xxx/list.jsp?wbtreeid=1018&a1020514p={page}&a1020514c=20
     */
    private String buildListUrl(CrawlerConfig.SourceConfig source, int page) {
        // ⭐ 模式 1：固定 URL（CMS 页面）
        String listUrl = source.getListUrl();
        if (StringUtils.hasText(listUrl)) {
            if (page == 1) {
                return listUrl;
            }
            // 博达 CMS 分页规则：
            //   wyhd.htm（第1页）→ wyhd/2.htm（第2页）→ wyhd/3.htm（第3页）
            //   sshd.htm（第1页）→ sshd/8.htm（第2页，降序页码）
            // 统一处理：去掉 .htm → 加 /{page}.htm
            if (listUrl.endsWith(".htm")) {
                return listUrl.substring(0, listUrl.length() - 4) + "/" + page + ".htm";
            }
            return listUrl + "/" + page;
        }

        // 模式 2：模板 URL（公文通 list.jsp）
        String template = source.getListUrlTemplate();
        if (template == null) return null;
        return template
                .replace("{page}", String.valueOf(page))
                .replace("{pageNum}", String.valueOf(page));
    }

    private String buildDetailUrl(CrawlerConfig.SourceConfig source, String id, String categoryCode) {
        String template = source.getDetailUrlTemplate();
        if (template == null) return null;
        return template
                .replace("{id}", id)
                .replace("{category}", categoryCode != null ? categoryCode :
                        (source.getCategoryCode() != null ? source.getCategoryCode() : ""));
    }

    private List<ListParserResult.InfoItemMeta> filterNewItems(
            List<ListParserResult.InfoItemMeta> items, String cachedLatestId) {

        if (!StringUtils.hasText(cachedLatestId) || "0".equals(cachedLatestId)) {
            return items;
        }

        long threshold = Long.parseLong(cachedLatestId);
        return items.stream()
                .filter(item -> {
                    try {
                        return Long.parseLong(item.getId()) > threshold;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                })
                .collect(Collectors.toList());
    }

    private List<ListParserResult.InfoItemMeta> crawlPages(
            CrawlerConfig.SourceConfig source, SmartSession session, int startPage, int endPage) {

        List<ListParserResult.InfoItemMeta> allItems = new CopyOnWriteArrayList<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int page = startPage; page <= endPage; page++) {
            final int p = page;
            futures.add(initExecutor.submit(() -> {
                try {
                    String url = buildListUrl(source, p);
                    SmartResponse resp = smartHttpClient.get(url, session);
                    if (resp.isSuccess()) {
                        ListParserResult result = parserFactory.parseList(
                                source.getParserType(), resp.getBody(), source, p);
                        if (result != null && result.getItems() != null) {
                            allItems.addAll(result.getItems());
                        }
                    }
                } catch (Exception e) {
                    log.warn("爬取第 {} 页失败: source={}, error={}", p, source.getId(), e.getMessage());
                }
            }));
        }

        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("等待爬取结果超时");
            }
        }

        return allItems;
    }

    private void broadcastNewContent(String channelId, List<ListParserResult.InfoItemMeta> newItems, String latestId) {
        Map<String, Object> data = new HashMap<>();
        data.put("channelId", channelId);
        data.put("count", newItems.size());
        data.put("latestId", latestId);
        data.put("ids", newItems.stream().map(ListParserResult.InfoItemMeta::getId).toList());
        if (!newItems.isEmpty()) {
            data.put("latestTitle", newItems.get(0).getTitle());
        }
        streamPublisher.publishToAll(StreamKeys.TYPE_NEW_ANNOUNCEMENTS, data);
    }
}