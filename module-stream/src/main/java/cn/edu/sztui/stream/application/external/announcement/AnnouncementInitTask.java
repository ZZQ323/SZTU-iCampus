package cn.edu.sztui.stream.application.external.announcement;

import cn.edu.sztui.base.application.vo.AnnouncementMetaVo;
import cn.edu.sztui.base.infrastructure.convertor.CookieConverter;
import cn.edu.sztui.base.infrastructure.util.cache.AnnouncementCacheUtil;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.base.infrastructure.util.praser.AnnouncementListParser;
import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.common.util.smarthttp.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 公告系统初始化任务 V2（基于 SmartHttpClient，无浏览器）
 * 
 * 【优势】：
 *  <ul>
 *      <li>无浏览器进程，内存占用极低（~20MB vs Playwright ~750MB）</li>
 *      <li>支持更高并发（20-50 vs Playwright 的 5）</li>
 *      <li>失败任务可快速重试</li>
 *      <li>初始化速度提升 5 倍</li>
 *  </ul>
 * 【触发时机】：
 * <ul>
 *     <li>首个用户登录成功后（通过 UserLoginEvent）</li>
 *     <li>手动调用（管理接口）</li>
 * </ul>
 */
@Slf4j
@Component
public class AnnouncementInitTask {

    /** 带总页数的列表 URL */
    private static final String LIST_URL_TEMPLATE =
            "https://nbw-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/list.jsp" +
                    "?totalpage=%d&PAGENUM=%d&wbtreeid=1029";

    /** 首页 URL（用于获取总页数）*/
    private static final String FIRST_PAGE_URL =
            "https://nbw-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/list.jsp?wbtreeid=1029";

    /**
     * 并发数（SmartHttpClient 可以设更高）
     * Playwright 版本只能设 5，HTTP 版本可以设 20-50
     */
    private static final int BATCH_CONCURRENCY = 20;

    /**
     * 批次间隔（毫秒），防止被封
     * 由于并发更高，间隔可以更短
     */
    private static final long BATCH_DELAY_MS = 2000;

    /**
     * 单次请求超时（秒）
     */
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    @Resource
    private AnnouncementCacheUtil announcementCacheUtil;

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    @Resource
    private SmartHttpClient smartHttpClient;

    @Resource
    private AnnouncementListParser listParser;

    /**
     * 是否正在初始化
     */
    private volatile boolean initializing = false;

    /**
     * 触发初始化（由 UserLoginEventListener 调用）
     * 
     * @param openId 登录用户的 openId（用于获取 Cookie）
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
            log.info("========== 开始公告系统初始化（SmartHttpClient V2）==========");
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

    /**
     * 执行初始化
     */
    private void doInit(String openId) {
        ProxySession session = authSessionCacheUtil.getSession(openId);
        if (session == null) {
            log.error("无法获取用户会话: {}", openId);
            return;
        }

        // 解析 Cookie
        List<SmartCookie> cookies = CookieConverter.jsonToSmartCookies(session.getCookiesJson());
        if (cookies.isEmpty()) {
            log.error("用户 Cookie 为空: {}", openId);
            return;
        }

        // Step 1: 获取总页数
        int totalPage = fetchTotalPage(cookies);
        if (totalPage <= 0) {
            log.error("无法获取总页数");
            return;
        }
        log.info("总页数: {}，预计公告数: ~{}", totalPage, totalPage * 20);

        // Step 2: 分批并发爬取
        List<AnnouncementMetaVo> allAnnouncements = new CopyOnWriteArrayList<>();
        int batchCount = (totalPage + BATCH_CONCURRENCY - 1) / BATCH_CONCURRENCY;

        // 创建固定大小的线程池
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
                            fetchSinglePage(cookies, finalTotalPage, currentPage)
                    ));
                }

                // 收集结果
                int successCount = 0;
                int failCount = 0;

                for (int i = 0; i < futures.size(); i++) {
                    int pageNum = startPage + i;
                    try {
                        List<AnnouncementMetaVo> result = futures.get(i).get(
                                REQUEST_TIMEOUT_SECONDS + 10, TimeUnit.SECONDS
                        );
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

                // 批次间隔（最后一批不需要）
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
        
        // 分批保存，避免单次操作过大
        int saveStart = 0;
        int saveBatchSize = 500;
        while (saveStart < allAnnouncements.size()) {
            int saveEnd = Math.min(saveStart + saveBatchSize, allAnnouncements.size());
            List<AnnouncementMetaVo> saveBatch = allAnnouncements.subList(saveStart, saveEnd);
            announcementCacheUtil.saveMetaBatch(new ArrayList<>(saveBatch));
            log.debug("已保存 {}/{} 条", saveEnd, allAnnouncements.size());
            saveStart = saveEnd;
        }

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
    private int fetchTotalPage(List<SmartCookie> cookies) {
        try (SmartSession session = smartHttpClient.newSession(cookies)) {
            SmartResponse response = smartHttpClient.get(FIRST_PAGE_URL, session);
            
            if (!response.isSuccess()) {
                log.error("获取首页失败: status={}", response.getStatusCode());
                return 0;
            }
            
            int totalPage = listParser.parseTotalPage(response.getBody());
            log.debug("解析到总页数: {}", totalPage);
            return totalPage;
            
        } catch (Exception e) {
            log.error("获取总页数失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 爬取单页
     * 
     * @param cookies 共享的 Cookie 列表
     * @param totalPage 总页数
     * @param pageNum 当前页码
     * @return 解析到的公告列表
     */
    private List<AnnouncementMetaVo> fetchSinglePage(List<SmartCookie> cookies, 
                                                      int totalPage, int pageNum) {
        // 每个任务创建独立的 SmartSession（共享连接池）
        try (SmartSession session = smartHttpClient.newSession(cookies)) {
            String url = String.format(LIST_URL_TEMPLATE, totalPage, pageNum);
            
            SmartResponse response = smartHttpClient.get(url, session);
            
            if (!response.isSuccess()) {
                log.warn("第 {} 页请求失败: status={}", pageNum, response.getStatusCode());
                return new ArrayList<>();
            }
            
            List<AnnouncementMetaVo> result = listParser.parseList(response.getBody());
            log.debug("第 {} 页完成，获取 {} 条", pageNum, result.size());
            return result;
            
        } catch (Exception e) {
            log.error("爬取第 {} 页失败: {}", pageNum, e.getMessage());
            return new ArrayList<>();
        }
    }

    // ==================== 状态查询 ====================

    /**
     * 是否已初始化
     */
    public boolean isInitialized() {
        return announcementCacheUtil.isSystemInitialized();
    }

    /**
     * 是否正在初始化
     */
    public boolean isInitializing() {
        return initializing;
    }

    /**
     * 获取初始化状态摘要
     */
    public String getStatusSummary() {
        if (initializing) {
            return "初始化中...";
        } else if (isInitialized()) {
            Long total = announcementCacheUtil.getTotalCount();
            String latestId = announcementCacheUtil.getLatestId();
            return String.format("已初始化，共 %d 条公告，最新ID: %s", 
                    total != null ? total : 0, latestId);
        } else {
            return "未初始化";
        }
    }
}
