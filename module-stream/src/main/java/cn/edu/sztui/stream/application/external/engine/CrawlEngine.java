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
import cn.edu.sztui.stream.infrastructure.websocket.registry.WsSessionRegistry;
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

    @Resource
    private WsSessionRegistry wsSessionRegistry;

    @Resource
    private CookiePoolMetrics cookiePoolMetrics;

    @Resource
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

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
        return crawlIncremental(sourceId, null);
    }

    /**
     * 增量爬取，可指定 userId 定向使用该用户的 cookies。
     * <p>
     * 用于教务内网等"特定用户的 session"语义的场景（acdm-* 需要调用 {@code /acdm/v1/init}
     * 的那个用户的 jwxt cookies），而非通用的 cookie 池轮换。
     */
    public CrawlResult crawlIncremental(String sourceId, String userId) {
        CrawlerConfig.SourceConfig source = configLoader.getSource(sourceId);
        if (source == null) {
            return CrawlResult.fail(sourceId, "未找到数据源配置: " + sourceId);
        }

        try {
            CookieSourceManager.CookieSessionPair pair = userId != null
                    ? resolveSessionPair(source, userId)
                    : resolveSessionPair(source);
            return doCrawlIncremental(source, pair);
        } catch (CookieSourceManager.NoCookieAvailableException e) {
            log.debug("增量爬取跳过（无 Cookie）: source={}", sourceId);
            return CrawlResult.fail(sourceId, e.getMessage());
        } catch (Exception e) {
            log.error("增量爬取失败: source={}, error={}", sourceId, e.getMessage(), e);
            return CrawlResult.fail(sourceId, e.getMessage());
        }
    }

    private CrawlResult doCrawlIncremental(CrawlerConfig.SourceConfig source,
                                           CookieSourceManager.CookieSessionPair pair) {
        String sourceId = source.getId();
        SmartSession session = pair.getSession();

        String listUrl = buildListUrl(source, 1);
        SmartResponse response = fetchPage(listUrl, session, source.isRequiresAuth());
        if (!response.isSuccess()) {
            int status = response.getStatusCode();
            // 认证失败检测：401/403 或重定向到登录页
            if (source.isRequiresAuth() && (status == 401 || status == 403)) {
                cookiePoolMetrics.recordAuthFail(pair.getUserId(), "HTTP " + status);
                return CrawlResult.authFail(sourceId, pair.getUserId(),
                        "Cookie 认证失败: HTTP " + status);
            }
            return CrawlResult.fail(sourceId, "HTTP 请求失败: " + status);
        }

        ListParserResult result = parserFactory.parseList(
                source.getParserType(), response.getBody(), source, 1);

        // ⭐ 解析器级别的认证失败（返回的是登录页 HTML 而非正常列表）
        if (result != null && result.isAuthExpired()) {
            cookiePoolMetrics.recordAuthFail(pair.getUserId(),
                    result.getErrorMessage() != null ? result.getErrorMessage() : "parser-login-page");
            return CrawlResult.authFail(sourceId, pair.getUserId(),
                    result.getErrorMessage() != null ? result.getErrorMessage() : "解析器检测到登录页");
        }

        if (result == null || result.getItems() == null || result.getItems().isEmpty()) {
            syncCookiesIfChanged(pair);
            return CrawlResult.empty(sourceId);
        }

        String channelId = source.getChannelId();
        String cachedLatestId = infoCacheUtil.getLatestId(channelId);
        List<ListParserResult.InfoItemMeta> newItems = filterNewItems(result.getItems(), cachedLatestId, channelId);

        if (newItems.isEmpty()) {
            infoCacheUtil.updateLastCrawlTime(sourceId);
            syncCookiesIfChanged(pair);
            return CrawlResult.empty(sourceId);
        }

        enrichItemsWithSourceMeta(newItems, source);
        infoCacheUtil.saveMetaBatch(channelId, newItems);
        publishArticleSavedEvents(newItems);

        String newLatestId = computeLatestId(newItems, cachedLatestId);
        infoCacheUtil.setLatestId(channelId, newLatestId);
        infoCacheUtil.updateLastCrawlTime(sourceId);

        broadcastNewContent(channelId, newItems, newLatestId);

        // 爬取完成后检测 cookie 变化
        syncCookiesIfChanged(pair);

        List<String> ids = newItems.stream().map(ListParserResult.InfoItemMeta::getId).toList();
        log.info("增量爬取完成: source={}, 新增 {} 条", sourceId, newItems.size());
        return CrawlResult.success(sourceId, newItems.size(), ids, newLatestId);
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
            // ⭐ 使用指定 userId 版本，避免登录后 WS 尚未连接的竞态
            CookieSourceManager.CookieSessionPair pair = resolveSessionPair(source, userId);
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
            publishArticleSavedEvents(firstPageItems);

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
                            publishArticleSavedEvents(remaining);

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
     * ⭐ 解析爬取 session（指定 userId 版本）
     * <p>
     * 用于 initSource 场景：UserLoginEvent 传入了明确的 userId，
     * 直接从 Redis 读取该用户的 Cookie，不通过 CookieSourceManager 搜索。
     * 避免登录后 WS 尚未连接、CookieSourceManager 找不到在线用户的竞态问题。
     */
    private CookieSourceManager.CookieSessionPair resolveSessionPair(CrawlerConfig.SourceConfig source, String userId) {
        if (!source.isRequiresAuth()) {
            return new CookieSourceManager.CookieSessionPair(null, smartHttpClient.newSession(), null);
        }

        // 优先使用指定的 userId 直接查找
        if (userId != null) {
            cn.edu.sztui.common.cache.dto.ProxySession proxy = authSessionCacheUtil.getSession(userId);
            if (proxy != null && org.springframework.util.StringUtils.hasText(proxy.getCookiesJson())) {
                List<SmartCookie> cookies = SmartCookieConverter.jsonToSmartCookies(proxy.getCookiesJson());
                List<SmartCookie> snapshot = new ArrayList<>(cookies);
                SmartSession session = smartHttpClient.newSession(cookies);
                log.info("使用指定用户 {} 的 Cookie 进行初始化", userId);
                return new CookieSourceManager.CookieSessionPair(userId, session, snapshot);
            }
            log.warn("指定用户 {} 的 Cookie 不可用，回退到 CookieSourceManager", userId);
        }

        // 回退到通用搜索
        return cookieSourceManager.getAvailableSessionWithUser();
    }

    /**
     * 爬取后检测 cookie 变化，有变化则更新 Redis 并推送给用户。
     * <p>
     * **硬守卫**（2026-04-25）：保存 + 推送之前必须确认这个 userId 还满足
     * "在线 + schoolLoggedIn + Redis 有 session"。否则发生过的真实 race：
     * <ol>
     *   <li>用户登出 → Redis 清空 + schoolLoggedIn=false</li>
     *   <li>in-flight 爬虫携带的 SmartSession 内存里还活着，跑完拿到一组新 cookies</li>
     *   <li>这里没守卫 → saveOrUpdateSessionCookie 把 cookies **复活** 写回 Redis</li>
     *   <li>pushCookieUpdate 把 cookies 推给前端（如果 WS 还在断连同步窗口里），
     *       前端 merge → cookies 也复活</li>
     *   <li>用户名义已登出，但实际后台爬虫还在借他 cookies，cookie 寿命被延长</li>
     * </ol>
     * 守卫断绝这条路径。
     */
    /**
     * 爬取后只**观测**前后 cookie 变化，**不再写回 Redis、不再推 WS**（2026-04-30 用户决策）。
     * <p>
     * 为什么禁用整条回写：
     * <ul>
     *   <li>这条路径反复成为"已登出 / 已过期 cookie 复活"的源头：爬虫拿到的"新"
     *       cookies 写回 Redis + 推 WS COOKIE_UPDATE 给前端 → 前端 merge 进 localStorage
     *       → 用户名义已退出，cookies 寿命被人为延长。</li>
     *   <li>历次都是靠"加守卫"修补（schoolLoggedIn 检查、在线检查、CAS 比较等），
     *       但并发覆盖窗口始终关不死。</li>
     *   <li>cookies 应当只在用户主动操作（登录 / 重置）时被写。爬取过程中即便学校
     *       下发了新 cookies，最多影响这次爬取的有效性，让它静默失败、下一轮重试就好。</li>
     * </ul>
     * 诊断 log 保留——若长期观察 changed=true 的频率为 0，下次可以连同
     * cookiesChanged + 这整个方法一起删掉，并清理 StreamPushService.pushCookieUpdate
     * 与前端 ws.ts 里的 COOKIE_UPDATE handler。
     */
    private void syncCookiesIfChanged(CookieSourceManager.CookieSessionPair pair) {
        if (pair.getUserId() == null || pair.getOriginalCookies() == null) return;
        String userId = pair.getUserId();

        int origCount = pair.getOriginalCookies().size();
        List<SmartCookie> currentCookies = pair.getSession().getCookies();
        int currCount = currentCookies.size();
        boolean changed = cookiesChanged(pair.getOriginalCookies(), currentCookies);
        boolean online = wsSessionRegistry.isOnline(userId);
        boolean loggedIn = authSessionCacheUtil.isSchoolLoggedIn(userId);
        log.info("[syncCookies] userId={} orig={} curr={} changed={} online={} loggedIn={} (writeback DISABLED)",
                userId, origCount, currCount, changed, online, loggedIn);
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

    /**
     * 过滤出"真正新增"的 item。
     * <p>
     * 双策略：
     * <ul>
     *   <li><b>数字 ID 快路径</b>：cachedLatestId 是数字 → 用 ID 数值比较（O(1) per item，0 redis）。
     *       适用于公文通、博达 CMS 这类自增 ID 频道。</li>
     *   <li><b>非数字 ID 兜底</b>：cachedLatestId 缺失或非数字（如 acdm-message 的
     *       {@code xxtz-<hash>}、acdm-notice 的 UUID 风格 ggid）→ 退化为
     *       {@code infoCacheUtil.hasMeta(channelId, id)} 精确去重（每条 1 redis HEXISTS）。
     *       这避免了"非数字 ID 频道每轮全部 20 条都被当成新增"的 bug，
     *       该 bug 会导致教务系统每 60s 被无谓地推送 20 条已存在内容到 WS。</li>
     * </ul>
     */
    private List<ListParserResult.InfoItemMeta> filterNewItems(
            List<ListParserResult.InfoItemMeta> items, String cachedLatestId, String channelId) {

        // 判断是否能走数字 ID 快路径
        Long numericThreshold = null;
        if (StringUtils.hasText(cachedLatestId) && !"0".equals(cachedLatestId)) {
            try {
                numericThreshold = Long.parseLong(cachedLatestId);
            } catch (NumberFormatException ignored) {
                // 非数字 latestId → 后面走 hasMeta 兜底
            }
        }

        final Long t = numericThreshold;
        return items.stream()
                .filter(item -> {
                    String id = item.getId();
                    if (t != null) {
                        // 数字 latestId 已知：item id 也是数字时直接比较；非数字时走 hasMeta
                        try {
                            return Long.parseLong(id) > t;
                        } catch (NumberFormatException e) {
                            return !infoCacheUtil.hasMeta(channelId, id);
                        }
                    }
                    // 没有 latestId 兜底（首次初始化 / 非数字 ID 频道）：用 hasMeta 精确去重
                    return !infoCacheUtil.hasMeta(channelId, id);
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

    /**
     * 逐条发布 ArticleSavedEvent。监听器（如 ActivityAutoScanListener）会按 @ConditionalOnProperty
     * 决定是否处理；默认情况下什么都不做，开启后（ai.activity.auto-process=true）才消费。
     */
    private void publishArticleSavedEvents(List<ListParserResult.InfoItemMeta> items) {
        if (items == null || items.isEmpty() || eventPublisher == null) return;
        for (ListParserResult.InfoItemMeta meta : items) {
            try {
                eventPublisher.publishEvent(
                        new cn.edu.sztui.stream.application.external.event.ArticleSavedEvent(this, meta));
            } catch (Exception e) {
                log.warn("publishArticleSavedEvent 失败: id={}, err={}", meta.getId(), e.getMessage());
            }
        }
    }

    private void broadcastNewContent(String channelId, List<ListParserResult.InfoItemMeta> newItems, String latestId) {
        Map<String, Object> data = buildBroadcastPayload(channelId, newItems, latestId);
        if (!newItems.isEmpty()) {
            // 论文证据 1：流式推送可见日志，便于演示和答辩
            ListParserResult.InfoItemMeta head = newItems.get(0);
            log.info("WS broadcast: channel={}, items={}, first='{}', sourceId={}",
                    channelId, newItems.size(), head.getTitle(), head.getSourceId());
        }
        streamPublisher.publishToAll(StreamKeys.TYPE_NEW_ANNOUNCEMENTS, data);
    }

    /**
     * 构造 WS NEW_ANNOUNCEMENTS payload。
     * <p>
     * <b>⭐ 流式推送硬规则（见 backend CLAUDE.md "WS payload 必须带完整 items"）</b>：
     * <ul>
     *   <li>{@code items} 必须是完整 {@link ListParserResult.InfoItemMeta} 列表</li>
     *   <li>前端直接 unshift 进 channelLists，<b>不允许再 HTTP fetch</b></li>
     *   <li>{@code ids/latestId/latestTitle/sourceId} 保留作向后兼容 + toast 便利字段</li>
     * </ul>
     * <p>
     * package-private 暴露用于单测验证流式契约。
     */
    static Map<String, Object> buildBroadcastPayload(String channelId,
                                                      List<ListParserResult.InfoItemMeta> newItems,
                                                      String latestId) {
        Map<String, Object> data = new HashMap<>();
        data.put("channelId", channelId);
        data.put("count", newItems.size());
        data.put("latestId", latestId);
        data.put("ids", newItems.stream().map(ListParserResult.InfoItemMeta::getId).toList());
        // ⭐ 核心字段：完整 items
        data.put("items", newItems);
        if (!newItems.isEmpty()) {
            ListParserResult.InfoItemMeta head = newItems.get(0);
            data.put("latestTitle", head.getTitle());
            // 单次爬取的 items 全部来自同一 source（enrichItemsWithSourceMeta 保证），
            // 带 sourceId/sourceOrgName 供前端做订阅过滤 + toast 显示
            data.put("sourceId", head.getSourceId());
            data.put("sourceOrgName", head.getSourceOrgName());
        }
        return data;
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
            // ⭐ channelId 必须设置：前端 handleItemClick 和后端 findSourceForDetail 都依赖它
            // 少了会导致 detail 请求被错误路由（例如 acdm-* 被路由成 gwt-jiaowu → 404）
            item.setChannelId(source.getChannelId());
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