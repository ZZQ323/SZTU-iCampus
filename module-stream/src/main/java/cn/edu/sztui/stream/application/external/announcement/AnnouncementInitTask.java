package cn.edu.sztui.stream.application.external.announcement;

import cn.edu.sztui.base.application.vo.AnnouncementMetaVo;
import cn.edu.sztui.base.infrastructure.convertor.CookieConverter;
import cn.edu.sztui.base.infrastructure.util.cache.AnnouncementCacheUtil;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.base.infrastructure.util.praser.AnnouncementListParser;
import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.common.util.browserpool.PlaywrightBrowserPool;
import com.microsoft.playwright.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 公告系统初始化任务
 *
 * 在首个用户登录成功后触发，全量爬取所有公告列表页
 */
@Slf4j
@Component
public class AnnouncementInitTask {

    private static final String LIST_URL_TEMPLATE =
            "https://nbw-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/list.jsp?totalpage=%d&PAGENUM=%d&wbtreeid=1029";

    private static final String FIRST_PAGE_URL =
            "https://nbw-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/list.jsp?wbtreeid=1029";

    /** 并发数 */
    private static final int CONCURRENCY = 20;

    /** 批次间隔（毫秒） */
    private static final long BATCH_DELAY_MS = 500;

    @Resource
    private AnnouncementCacheUtil announcementCacheUtil;

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    @Resource
    private PlaywrightBrowserPool browserPool;

    @Resource
    private AnnouncementListParser listParser;

    /** 是否正在初始化 */
    private volatile boolean initializing = false;

    /**
     * 触发初始化
     * 不再使用 @Async，由调用方（EventListener）控制异步
     *
     * @param openId 触发用户的 openId
     */
    @Async
    public void triggerInit(String openId) {
        // 检查是否已初始化
        if (announcementCacheUtil.isSystemInitialized()) {
            log.info("公告系统已初始化，跳过");
            return;
        }

        // 防止重复触发
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
        log.info("总页数: {}", totalPage);

        // Step 2: 分批并发爬取
        List<AnnouncementMetaVo> allAnnouncements = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);

        try {
            int batchCount = (totalPage + CONCURRENCY - 1) / CONCURRENCY;

            for (int batch = 0; batch < batchCount; batch++) {
                int startPage = batch * CONCURRENCY + 1;
                int endPage = Math.min((batch + 1) * CONCURRENCY, totalPage);

                log.info("爬取批次 {}/{}: 第 {} - {} 页", batch + 1, batchCount, startPage, endPage);

                List<Future<List<AnnouncementMetaVo>>> futures = new ArrayList<>();

                for (int page = startPage; page <= endPage; page++) {
                    final int currentPage = page;
                    final int finalTotalPage = totalPage;

                    futures.add(executor.submit(() ->
                            fetchPage(session, finalTotalPage, currentPage)
                    ));
                }

                // 收集结果
                for (Future<List<AnnouncementMetaVo>> future : futures) {
                    try {
                        List<AnnouncementMetaVo> pageResult = future.get(60, TimeUnit.SECONDS);
                        if (pageResult != null) {
                            allAnnouncements.addAll(pageResult);
                        }
                    } catch (Exception e) {
                        log.warn("获取页面结果失败: {}", e.getMessage());
                    }
                }

                // 批次间隔
                if (batch < batchCount - 1) {
                    Thread.sleep(BATCH_DELAY_MS);
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("初始化被中断", e);
        } finally {
            executor.shutdown();
        }

        // Step 3: 保存到 Redis
        if (allAnnouncements.isEmpty()) {
            log.warn("未获取到任何公告数据");
            return;
        }

        log.info("开始保存 {} 条公告到 Redis...", allAnnouncements.size());
        announcementCacheUtil.saveMetaBatch(allAnnouncements);

        // 设置最新 ID
        String latestId = allAnnouncements.stream()
                .map(m -> Long.parseLong(m.getId()))
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
            page.navigate(FIRST_PAGE_URL);
            page.waitForLoadState();

            String html = page.content();
            return listParser.parseTotalPage(html);

        }, browserPool.getDefaultTimeoutSeconds());
    }

    /**
     * 爬取单页
     */
    private List<AnnouncementMetaVo> fetchPage(ProxySession session, int totalPage, int pageNum) {
        try {
            return browserPool.executeWithContext(context -> {
                context.addCookies(CookieConverter.fromCookieDTOs(session.getCookiesJson()));

                Page page = context.newPage();
                String url = String.format(LIST_URL_TEMPLATE, totalPage, pageNum);
                page.navigate(url);
                page.waitForLoadState();

                String html = page.content();
                List<AnnouncementMetaVo> result = listParser.parseList(html);

                log.debug("第 {} 页爬取完成，获取 {} 条", pageNum, result.size());
                return result;

            }, browserPool.getDefaultTimeoutSeconds());

        } catch (Exception e) {
            log.error("爬取第 {} 页失败: {}", pageNum, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return announcementCacheUtil.isSystemInitialized();
    }

    /**
     * 检查是否正在初始化
     */
    public boolean isInitializing() {
        return initializing;
    }
}