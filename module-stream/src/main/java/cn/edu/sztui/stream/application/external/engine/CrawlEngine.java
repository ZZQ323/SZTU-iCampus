package cn.edu.sztui.stream.application.external.engine;

import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.common.util.smarthttp.SmartCookieConverter;
import cn.edu.sztui.common.util.smarthttp.dto.SmartCookie;
import cn.edu.sztui.common.util.smarthttp.dto.SmartRequest;
import cn.edu.sztui.common.util.smarthttp.dto.SmartResponse;
import cn.edu.sztui.common.util.smarthttp.service.SmartHttpClient;
import cn.edu.sztui.common.util.smarthttp.service.SmartSession;
import cn.edu.sztui.stream.application.service.StreamPushService;
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
 * ⭐ 本次改动：两阶段初始化
 * 阶段 1（同步）：爬第 1 页，立即存 Redis + 标记 initialized → 用户 0 等待
 * 阶段 2（异步）：后台爬剩余页，逐批追加到 Redis → 无感补全历史数据
 * <p>
 * 爬取上限：默认最多 10 页（约 200 条），避免对学校服务器造成压力。
 * 可通过 sources.yml 的 crawlPageCount 覆盖。
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

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    @Resource
    private StreamPushService streamPushService;

    /**
     * 后台初始化线程池（阶段 2 用）
     */
    private final ExecutorService initExecutor = Executors.newFixedThreadPool(30);

    /**
     * 多页并发爬取线程池
     */
    private final ExecutorService pageExecutor = Executors.newFixedThreadPool(10);

    /**
     * 默认最大爬取页数（无 crawlPageCount 配置时的上限）
     */
    // private static final int DEFAULT_MAX_PAGES = 10;

    // ==================== 增量爬取 ====================
    public CrawlResult crawlIncremental(String sourceId) {
        CrawlerConfig.SourceConfig source = configLoader.getSource(sourceId);
        if (source == null) {
            return CrawlResult.fail(sourceId, "未找到数据源配置: " + sourceId);
        }

        try {
            CookieSourceManager.CookieSessionPair pair = resolveSessionPair(source);
            SmartSession session = pair.getSession();

            String listUrl = buildListUrl(source, 1);
            SmartResponse response = fetchPage(listUrl, session, source.isRequiresAuth());
            if (!response.isSuccess()) {
                int status = response.getStatusCode();
                // 认证失败检测：401/403 或重定向到登录页
                if (source.isRequiresAuth() && (status == 401 || status == 403)) {
                    return CrawlResult.authFail(sourceId, pair.getUserId(),
                            "Cookie 认证失败: HTTP " + status);
                }
                return CrawlResult.fail(sourceId, "HTTP 请求失败: " + status);
            }

            ListParserResult result = parserFactory.parseList(
                    source.getParserType(), response.getBody(), source, 1);

            if (result == null || result.getItems() == null || result.getItems().isEmpty()) {
                syncCookiesIfChanged(pair);
                return CrawlResult.empty(sourceId);
            }

            String channelId = source.getChannelId();
            String cachedLatestId = infoCacheUtil.getLatestId(channelId);
            List<ListParserResult.InfoItemMeta> newItems = filterNewItems(result.getItems(), cachedLatestId);

            if (newItems.isEmpty()) {
                infoCacheUtil.updateLastCrawlTime(sourceId);
                syncCookiesIfChanged(pair);
                return CrawlResult.empty(sourceId);
            }

            enrichItemsWithSourceMeta(newItems, source);
            infoCacheUtil.saveMetaBatch(channelId, newItems);

            String newLatestId = computeLatestId(newItems, cachedLatestId);
            infoCacheUtil.setLatestId(channelId, newLatestId);
            infoCacheUtil.updateLastCrawlTime(sourceId);

            broadcastNewContent(channelId, newItems, newLatestId);

            // 爬取完成后检测 cookie 变化
            syncCookiesIfChanged(pair);

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

    // ==================== ⭐ 两阶段初始化 ====================

    public void initSource(String sourceId, String userId) {
        CrawlerConfig.SourceConfig source = configLoader.getSource(sourceId);
        if (source == null) {
            log.error("初始化失败，未找到数据源: {}", sourceId);
            return;
        }

        String channelId = source.getChannelId();

        if (infoCacheUtil.isSourceInitialized(sourceId)) {
            log.debug("数据源已初始化，跳过: {}", sourceId);
            return;
        }

        long startTime = System.currentTimeMillis();
        log.info("======== 开始初始化数据源: {} ({}) ========", source.getName(), sourceId);

        try {
            CookieSourceManager.CookieSessionPair pair = resolveSessionPair(source);
            SmartSession session = pair.getSession();

            // ==================== 阶段 1：同步爬第 1 页 ====================

            String firstPageUrl = buildListUrl(source, 1);
            SmartResponse firstResponse = fetchPage(firstPageUrl, session, source.isRequiresAuth());
            if (!firstResponse.isSuccess()) {
                log.error("获取首页失败: source={}, status={}", sourceId, firstResponse.getStatusCode());
                return;
            }

            ListParserResult firstResult = parserFactory.parseList(
                    source.getParserType(), firstResponse.getBody(), source, 1);

            if (firstResult == null || firstResult.getItems() == null || firstResult.getItems().isEmpty()) {
                log.warn("初始化无结果: {}", sourceId);
                // 即使无结果也标记已初始化，避免反复重试
                infoCacheUtil.markSourceInitialized(sourceId);
                return;
            }

            // 保存第 1 页数据
            List<ListParserResult.InfoItemMeta> firstPageItems = firstResult.getItems();
            enrichItemsWithSourceMeta(firstPageItems, source);
            infoCacheUtil.saveMetaBatch(channelId, firstPageItems);

            String latestId = computeLatestId(firstPageItems, "0");
            infoCacheUtil.setLatestId(channelId, latestId);

            // ⭐ 立即标记为 initialized → 用户马上能看到第 1 页数据
            infoCacheUtil.markSourceInitialized(sourceId);

            // 阶段 1 完成后检测 cookie 变化
            syncCookiesIfChanged(pair);

            long phase1Ms = System.currentTimeMillis() - startTime;
            log.info("阶段1完成: {} - {} 条, {}ms（用户可见）", sourceId, firstPageItems.size(), phase1Ms);

            // ==================== 阶段 2：异步爬剩余页 ====================

            int totalPagetmp = (firstResult.getTotalPages() != null) ? firstResult.getTotalPages() : 1;
            if (totalPagetmp <= 0) totalPagetmp = 1;
            final int totalPage = totalPagetmp;
            // 计算要爬的总页数
            int maxPages = resolveMaxPages(source, totalPage);

            if (maxPages > 1) {
                final int pagesToCrawl = maxPages;
                final String finalLatestId = latestId;

                initExecutor.submit(() -> {
                    try {
                        log.info("阶段2开始: {} - 后台爬取第 2~{} 页（共 {} 页可用）",
                                sourceId, pagesToCrawl, totalPage);

                        List<ListParserResult.InfoItemMeta> remaining =
                                crawlPagesInBatches(source, session, 2, pagesToCrawl, totalPage);

                        if (!remaining.isEmpty()) {
                            enrichItemsWithSourceMeta(remaining, source);
                            infoCacheUtil.saveMetaBatch(channelId, remaining);

                            // 更新 latestId（可能有更大的 ID）
                            String newLatest = computeLatestId(remaining, finalLatestId);
                            infoCacheUtil.setLatestId(channelId, newLatest);

                            long totalMs = System.currentTimeMillis() - startTime;
                            log.info("阶段2完成: {} - 追加 {} 条, 总耗时 {}s",
                                    sourceId, remaining.size(), totalMs / 1000);
                        } else {
                            log.info("阶段2完成: {} - 无追加数据", sourceId);
                        }

                        // 阶段 2 完成后再次检测 cookie 变化
                        syncCookiesIfChanged(pair);

                    } catch (Exception e) {
                        log.warn("阶段2失败（不影响已有数据）: source={}, error={}",
                                sourceId, e.getMessage());
                    }
                });
            } else {
                log.info("======== 初始化完成: {} - {} 条, {}ms（单页） ========",
                        sourceId, firstPageItems.size(), phase1Ms);
            }

        } catch (Exception e) {
            log.error("初始化失败: source={}, error={}", sourceId, e.getMessage(), e);
        }
    }

    // ==================== 详情爬取 ====================

    public ContentParserResult crawlDetail(String sourceId, String id, String categoryCode) {
        CrawlerConfig.SourceConfig source = configLoader.getSource(sourceId);
        if (source == null) return null;

        try {
            CookieSourceManager.CookieSessionPair pair = resolveSessionPair(source);
            SmartSession session = pair.getSession();
            String detailUrl = buildDetailUrl(source, id, categoryCode);

            SmartResponse response = fetchPage(detailUrl, session, source.isRequiresAuth());
            if (!response.isSuccess()) {
                log.error("爬取详情失败: source={}, id={}, status={}", sourceId, id, response.getStatusCode());
                return null;
            }

            ContentParserResult content = parserFactory.parseContent(
                    source.getParserType(), response.getBody(), source, id);
            if (content != null) {
                content.setId(id);
            }

            syncCookiesIfChanged(pair);
            return content;

        } catch (Exception e) {
            log.error("爬取详情异常: source={}, id={}, error={}", sourceId, id, e.getMessage());
            return null;
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 解析爬取 session
     * <p>
     * 返回 CookieSessionPair：需要认证时包含 userId 和原始 cookies 快照，不需要认证时 userId 为 null。
     */
    private CookieSourceManager.CookieSessionPair resolveSessionPair(CrawlerConfig.SourceConfig source) {
        if (source.isRequiresAuth()) {
            return cookieSourceManager.getAvailableSessionWithUser();
        }
        return new CookieSourceManager.CookieSessionPair(null, smartHttpClient.newSession(), null);
    }

    /**
     * 爬取后检测 cookie 变化，有变化则更新 Redis 并推送给用户
     */
    private void syncCookiesIfChanged(CookieSourceManager.CookieSessionPair pair) {
        if (pair.getUserId() == null || pair.getOriginalCookies() == null) return;

        List<SmartCookie> currentCookies = pair.getSession().getCookies();
        if (cookiesChanged(pair.getOriginalCookies(), currentCookies)) {
            String userId = pair.getUserId();
            authSessionCacheUtil.saveOrUpdateSessionCookie(userId, currentCookies);
            String newJson = SmartCookieConverter.smartCookiesToJson(currentCookies);
            streamPushService.pushCookieUpdate(userId, newJson);
            log.info("爬取过程中 Cookie 变化，已同步: userId={}", userId);
        }
    }

    /**
     * 比较两组 cookies 是否有变化（按 name=value 集合比较）
     */
    private boolean cookiesChanged(List<SmartCookie> original, List<SmartCookie> current) {
        if (original.size() != current.size()) return true;
        var originalSet = new java.util.HashSet<String>();
        for (SmartCookie c : original) {
            originalSet.add(c.getName() + "=" + c.getValue());
        }
        for (SmartCookie c : current) {
            if (!originalSet.contains(c.getName() + "=" + c.getValue())) return true;
        }
        return false;
    }

    /**
     * 构建列表页 URL
     */
    private String buildListUrl(CrawlerConfig.SourceConfig source, int page) {
        return buildListUrl(source, page, -1);
    }

    /**
     * 构建列表页 URL（支持倒序分页）
     *
     * @param totalPages 总页数（倒序分页时需要，-1 表示未知）
     */
    private String buildListUrl(CrawlerConfig.SourceConfig source, int page, int totalPages) {
        // 模式 1：固定 URL
        String listUrl = source.getListUrl();
        if (StringUtils.hasText(listUrl)) {
            if (page == 1) return listUrl;

            int pathNum = page;
            // 倒序分页：page N → path (totalPages - N + 1)
            if (source.isPaginationReverse() && totalPages > 0) {
                pathNum = totalPages - page + 1;
                if (pathNum < 1) pathNum = 1;
                log.debug("倒序分页: page={} → pathNum={} (totalPages={})", page, pathNum, totalPages);
            }

            if (listUrl.endsWith(".htm")) {
                return listUrl.substring(0, listUrl.length() - 4) + "/" + pathNum + ".htm";
            }
            return listUrl + "/" + pathNum;
        }

        // 模式 2：模板 URL
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

    /**
     * 计算最大爬取页数
     * <p>
     * 优先级：crawlPageCount（YAML 配置）> DEFAULT_MAX_PAGES（10）> totalPage
     */
    private int resolveMaxPages(CrawlerConfig.SourceConfig source, int totalPage) {
        Integer configured = source.getCrawlPageCount();
        if (configured != null && configured > 0) {
            // YAML 显式配置了页数
            return Math.min(configured, totalPage);
        }
        // 未配置：用默认上限（10 页 ≈ 200 条）
//        return Math.min(DEFAULT_MAX_PAGES, totalPage);
        return totalPage;
    }

    /**
     * 分批爬取多页（阶段 2 用）
     * <p>
     * 每 3 页一批并发，批间间隔 500ms，避免对学校服务器造成压力。
     */
    private List<ListParserResult.InfoItemMeta> crawlPagesInBatches(
            CrawlerConfig.SourceConfig source, SmartSession session,
            int startPage, int endPage, int totalPages) {

        List<ListParserResult.InfoItemMeta> allItems = new CopyOnWriteArrayList<>();
        int batchSize = 3; // 每批并发 3 页

        for (int batchStart = startPage; batchStart <= endPage; batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize - 1, endPage);
            List<Future<?>> futures = new ArrayList<>();

            for (int page = batchStart; page <= batchEnd; page++) {
                final int p = page;
                futures.add(pageExecutor.submit(() -> {
                    try {
                        String url = buildListUrl(source, p, totalPages);
                        SmartResponse resp = fetchPage(url, session, source.isRequiresAuth());
                        if (resp.isSuccess()) {
                            ListParserResult result = parserFactory.parseList(
                                    source.getParserType(), resp.getBody(), source, p);
                            if (result != null && result.getItems() != null) {
                                allItems.addAll(result.getItems());
                                log.debug("后台爬取第 {} 页: source={}, items={}",
                                        p, source.getId(), result.getItems().size());
                            }
                        } else {
                            log.warn("后台爬取第 {} 页失败: source={}, status={}",
                                    p, source.getId(), resp.getStatusCode());
                        }
                    } catch (Exception e) {
                        log.warn("后台爬取第 {} 页异常: source={}, error={}",
                                p, source.getId(), e.getMessage());
                    }
                }));
            }

            // 等待当前批次完成
            for (Future<?> f : futures) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("等待爬取结果超时");
                }
            }

            // 批间间隔，对学校服务器友好
            if (batchEnd < endPage) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return allItems;
    }

    private List<ListParserResult.InfoItemMeta> filterNewItems(
            List<ListParserResult.InfoItemMeta> items, String cachedLatestId) {

        if (!StringUtils.hasText(cachedLatestId) || "0".equals(cachedLatestId)) {
            return items;
        }

        long threshold;
        try {
            threshold = Long.parseLong(cachedLatestId);
        } catch (NumberFormatException e) {
            // 非数字 ID（如 wx_xxx），无法比较，返回全部
            return items;
        }

        return items.stream()
                .filter(item -> {
                    try {
                        return Long.parseLong(item.getId()) > threshold;
                    } catch (NumberFormatException e) {
                        // 非数字 ID 的条目始终视为"新"
                        return true;
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * 从条目列表中计算最大 ID
     */
    private String computeLatestId(List<ListParserResult.InfoItemMeta> items, String currentLatest) {
        long max = 0;
        try {
            max = Long.parseLong(currentLatest);
        } catch (NumberFormatException ignored) {
        }

        for (ListParserResult.InfoItemMeta item : items) {
            try {
                long id = Long.parseLong(item.getId());
                if (id > max) max = id;
            } catch (NumberFormatException ignored) {
                // wx_xxx / ext_xxx 类型的 ID 跳过
            }
        }

        return String.valueOf(max);
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

    /**
     * 为每条 item 打上数据源的元数据标签（sourceId, contentType, subContentType, sourceOrg, sourceOrgName）。
     * 在保存到 Redis 之前调用。
     */
    private void enrichItemsWithSourceMeta(List<ListParserResult.InfoItemMeta> items, CrawlerConfig.SourceConfig source) {
        CrawlerConfig.ChannelConfig channel = configLoader.findChannelById(source.getChannelId());
        String sourceOrgName = channel != null ? channel.getName() : source.getName();
        String sourceOrg = channel != null ? channel.getSourceOrg() : "unknown";

        for (ListParserResult.InfoItemMeta item : items) {
            item.setSourceId(source.getId());
            item.setContentType(source.getContentType());
            item.setSubContentType(source.getSubContentType());
            item.setSourceOrg(sourceOrg);
            item.setSourceOrgName(sourceOrgName);
        }
    }

    /**
     * 获取页面内容。
     * <p>
     * 公开源（requiresAuth=false）不跟随重定向，因为学院 CMS 页面直接返回 200 HTML，
     * 跟随重定向会被 JS/Meta redirect 引导到不存在的移动端页面（page.html/wap/index.jsp）。
     * 需要登录的源（公文通等）需要跟随 WebVPN 重定向链。
     */
    private SmartResponse fetchPage(String url, SmartSession session, boolean requiresAuth) {
        if (requiresAuth) {
            return smartHttpClient.get(url, session);
        } else {
            SmartRequest request = SmartRequest.get(url);
            return smartHttpClient.executeNoRedirect(request, session);
        }
    }
}