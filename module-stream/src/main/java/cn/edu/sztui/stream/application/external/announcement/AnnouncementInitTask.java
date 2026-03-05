package cn.edu.sztui.stream.application.external.announcement;

import cn.edu.sztui.base.application.vo.AnnouncementMetaVo;
import cn.edu.sztui.base.infrastructure.convertor.CookieConverter;
import cn.edu.sztui.base.infrastructure.util.cache.AnnouncementCacheUtil;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.base.infrastructure.util.praser.AnnouncementListParser;
import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.common.util.browserpool.PlaywrightBrowserPoolCommonsVersion;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 公告系统初始化任务
 * <p>
 * 在首个用户登录成功后触发，全量爬取所有公告列表页
 * 采用批处理并发策略，避免过度并发导致资源耗尽
 */
@Slf4j
@Component
public class AnnouncementInitTask {

    private static final String LIST_URL_TEMPLATE =
            "https://nbw-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/list.jsp?totalpage=%d&PAGENUM=%d&wbtreeid=1029";

    private static final String FIRST_PAGE_URL =
            "https://nbw-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/list.jsp?wbtreeid=1029";

    /**
     * 每批并发数（不要超过池大小）
     */
    private static final int BATCH_CONCURRENCY = 5;

    /**
     * 批次间隔（毫秒），防止被封
     */
    private static final long BATCH_DELAY_MS = 5000;

    /**
     * 单页爬取超时（秒）
     */
    private static final int PAGE_TIMEOUT_SECONDS = 30;

    @Resource
    private AnnouncementCacheUtil announcementCacheUtil;

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    @Resource
    private PlaywrightBrowserPoolCommonsVersion browserPool;

    @Resource
    private AnnouncementListParser listParser;

    /**
     * 是否正在初始化
     */
    private volatile boolean initializing = false;

    /**
     * 触发初始化（由异步事件监听器调用）
     */
    public void triggerInit(String openId) {
        if (announcementCacheUtil.isSystemInitialized()) {
            log.info("公告系统已初始化，跳过");
            return;
        }

        if (initializing) {
            log.info("公告系统正在初始化中，跳过重复触发");
            return;
        }

        initializing = true;

        try {
            log.info("========== 开始公告系统初始化 ==========");
            long startTime = System.currentTimeMillis();

            doInit(openId);

            long duration = System.currentTimeMillis() - startTime;
            log.info("========== 公告系统初始化完成，耗时 {} 秒 ==========", duration / 1000);

        } catch (Exception e) {
            log.error("公告系统初始化失败", e);
        } finally {
            initializing = false;
        }
    }

    private void doInit(String openId) {
        ProxySession session = authSessionCacheUtil.getSession(openId);
        if (session == null) {
            log.error("无法获取用户会话: {}", openId);
            return;
        }

        // Step 1: 获取总页数
        int totalPage = fetchTotalPage(session);
        if (totalPage <= 0) {
            log.error("无法获取总页数");
            return;
        }
        log.info("总页数: {}，预计公告数: {}", totalPage, totalPage * 20);

        // Step 2: 分批并发爬取
        List<AnnouncementMetaVo> allAnnouncements = new CopyOnWriteArrayList<>();
        int batchCount = (totalPage + BATCH_CONCURRENCY - 1) / BATCH_CONCURRENCY;

        // 创建一个固定大小的线程池
        ExecutorService executor = Executors.newFixedThreadPool(BATCH_CONCURRENCY);

        try {
            for (int batch = 0; batch < batchCount; batch++) {
                int startPage = batch * BATCH_CONCURRENCY + 1;
                int endPage = Math.min((batch + 1) * BATCH_CONCURRENCY, totalPage);

                log.info("爬取批次 {}/{}: 第 {} - {} 页", batch + 1, batchCount, startPage, endPage);

                // 提交当前批次的所有任务
                List<Future<List<AnnouncementMetaVo>>> futures = new ArrayList<>();

                for (int pageNum = startPage; pageNum <= endPage; pageNum++) {
                    final int currentPage = pageNum;
                    final int finalTotalPage = totalPage;

                    futures.add(executor.submit(() ->
                            fetchSinglePage(session, finalTotalPage, currentPage)
                    ));
                }

                // 等待当前批次所有任务完成
                int successCount = 0;
                int failCount = 0;

                for (int i = 0; i < futures.size(); i++) {
                    int pageNum = startPage + i;
                    try {
                        List<AnnouncementMetaVo> result = futures.get(i).get(60, TimeUnit.SECONDS);
                        if (result != null && !result.isEmpty()) {
                            allAnnouncements.addAll(result);
                            successCount++;
                        } else {
                            failCount++;
                        }
                    } catch (TimeoutException e) {
                        log.warn("第 {} 页爬取超时", pageNum);
                        futures.get(i).cancel(true);
                        failCount++;
                    } catch (Exception e) {
                        log.warn("第 {} 页结果获取失败: {}", pageNum, e.getMessage());
                        failCount++;
                    }
                }

                log.info("批次 {}/{} 完成 - 成功: {}, 失败: {}, 累计: {} 条",
                        batch + 1, batchCount, successCount, failCount, allAnnouncements.size());

                // 打印池状态
                browserPool.logPoolStats();

                // 批次间隔
                if (batch < batchCount - 1) {
                    Thread.sleep(BATCH_DELAY_MS);
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("初始化被中断");
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
        }

        // Step 3: 保存到 Redis
        if (allAnnouncements.isEmpty()) {
            log.warn("未获取到任何公告数据");
            return;
        }

        log.info("开始保存 {} 条公告到 Redis...", allAnnouncements.size());
        announcementCacheUtil.saveMetaBatch(new ArrayList<>(allAnnouncements));

        // 设置最新 ID
        String latestId = allAnnouncements.stream()
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

        announcementCacheUtil.setLatestId(latestId);
        announcementCacheUtil.setSystemInitialized(true);
        announcementCacheUtil.updateLastCrawlTime();

        log.info("公告系统初始化完成：共 {} 条公告，最新 ID: {}", allAnnouncements.size(), latestId);
    }

    /**
     * 获取总页数
     */
    private int fetchTotalPage(ProxySession session) {
        return browserPool.executeWithContext(context -> {
            context.addCookies(CookieConverter.fromCookieDTOs(session.getCookiesJson()));

            Page page = context.newPage();
            try {
                page.navigate(FIRST_PAGE_URL);
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);

                String html = page.content();
                return listParser.parseTotalPage(html);
            } finally {
                // 确保页面关闭
                closePage(page);
            }
        }, PAGE_TIMEOUT_SECONDS);
    }

    /**
     * 爬取单页（每个任务独立获取 context）
     */
    private List<AnnouncementMetaVo> fetchSinglePage(ProxySession session, int totalPage, int pageNum) {
        try {
            return browserPool.executeWithContext(context -> {
                context.addCookies(CookieConverter.fromCookieDTOs(session.getCookiesJson()));

                Page page = context.newPage();
                try {
                    String url = String.format(LIST_URL_TEMPLATE, totalPage, pageNum);

                    // 设置页面级别超时
                    page.setDefaultTimeout(PAGE_TIMEOUT_SECONDS * 1000L);
                    page.setDefaultNavigationTimeout(PAGE_TIMEOUT_SECONDS * 1000L);

                    page.navigate(url);
                    page.waitForLoadState(LoadState.LOAD);

                    String html = page.content();
                    // log.info("html: {}",html);
                    List<AnnouncementMetaVo> result = listParser.parseList(html);

                    log.info("第 {} 页完成，获取 {} 条", pageNum, result.size());
                    return result;

                } finally {
                    // ⭐ 确保页面关闭，防止卡住
                    closePage(page);
                }
            }, PAGE_TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.error("爬取第 {} 页失败: {}", pageNum, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 安全关闭页面
     */
    private void closePage(Page page) {
        if (page != null) {
            try {
                if (!page.isClosed()) {
                    page.close();
                }
            } catch (Exception e) {
                log.warn("关闭页面失败: {}", e.getMessage());
            }
        }
    }

    public boolean isInitialized() {
        return announcementCacheUtil.isSystemInitialized();
    }

    public boolean isInitializing() {
        return initializing;
    }
}