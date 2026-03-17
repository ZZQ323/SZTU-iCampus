package cn.edu.sztui.stream.application.external.announcement;

import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.stream.application.service.AnnouncementService;
import cn.edu.sztui.stream.infrastructure.persistence.entity.textDTO.AnnouncementMetaVo;
import cn.edu.sztui.stream.infrastructure.util.cache.AnnouncementCacheUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 公告系统初始化任务
 * 
 * 在首个用户登录后触发，全量爬取所有公告列表
 * 并预爬取最新 50 篇详情
 * 
 * 文件位置：module-stream/src/main/java/cn/edu/sztui/stream/application/external/announcement/AnnouncementInitTask.java
 */
@Slf4j
@Component
public class AnnouncementInitTask {

    @Resource
    private AnnouncementService announcementService;

    @Resource
    private AnnouncementCacheUtil announcementCacheUtil;

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    /** 初始化锁，防止重复执行 */
    private final AtomicBoolean initializing = new AtomicBoolean(false);

    /** 并发爬取线程池 */
    private final ExecutorService crawlExecutor = Executors.newFixedThreadPool(5);

    /** 每批次爬取的页数 */
    private static final int BATCH_SIZE = 10;

    /** 预爬取详情数量 */
    private static final int PRE_CRAWL_DETAILS = 50;

    /**
     * 触发初始化
     * 
     * @param openId 首个登录用户的 openId
     */
    public void triggerInit(String openId) {
        // 检查是否已初始化
        if (announcementCacheUtil.isSystemInitialized()) {
            log.info("公告系统已初始化，跳过");
            return;
        }

        // 防止重复初始化
        if (!initializing.compareAndSet(false, true)) {
            log.info("公告系统正在初始化中，跳过重复触发");
            return;
        }

        try {
            long startTime = System.currentTimeMillis();
            log.info("========== 开始公告系统初始化 ==========");

            // 执行初始化
            doInit(openId);

            long duration = System.currentTimeMillis() - startTime;
            log.info("========== 公告系统初始化完成，耗时 {} 秒 ==========", duration / 1000);

        } catch (Exception e) {
            log.error("公告系统初始化失败", e);
        } finally {
            initializing.set(false);
        }
    }

    /**
     * 执行初始化
     */
    private void doInit(String openId) {
        // 1. 验证用户 Session
        ProxySession session = authSessionCacheUtil.getSession(openId);
        if (session == null) {
            log.error("无法获取用户会话: {}", openId);
            return;
        }

        // 检查是否已登录学校系统
        if (!session.isSchoolLoggedIn()) {
            log.error("用户未登录学校系统: {}", openId);
            return;
        }

        // 2. 获取总页数
        int totalPage = announcementService.getTotalPage(openId);
        if (totalPage <= 0) {
            log.error("无法获取总页数");
            return;
        }
        log.info("总页数: {}，预计公告数: ~{}", totalPage, totalPage * 20);

        // 3. 并发爬取所有页
        List<AnnouncementMetaVo> allAnnouncements = crawlAllPages(openId, totalPage);

        if (allAnnouncements.isEmpty()) {
            log.warn("未获取到任何公告数据");
            return;
        }

        // 4. 保存到缓存
        saveToCache(allAnnouncements);

        // 5. 预爬取详情
        log.info("========== 开始预爬取详情（最新{}篇）==========", PRE_CRAWL_DETAILS);
        List<String> topIds = allAnnouncements.stream()
                .limit(PRE_CRAWL_DETAILS)
                .map(AnnouncementMetaVo::getAnnouncementId)
                .toList();
        announcementService.preCrawlDetails(openId, topIds);

        // 6. 标记初始化完成
        announcementCacheUtil.setSystemInitialized(true);
        log.info("公告系统初始化完成：共 {} 条公告", allAnnouncements.size());
    }

    /**
     * 并发爬取所有页
     */
    private List<AnnouncementMetaVo> crawlAllPages(String openId, int totalPage) {
        List<AnnouncementMetaVo> allAnnouncements = new ArrayList<>();

        // 分批次并发爬取
        int batchCount = (totalPage + BATCH_SIZE - 1) / BATCH_SIZE;

        for (int batch = 0; batch < batchCount; batch++) {
            int startPage = batch * BATCH_SIZE + 1;
            int endPage = Math.min((batch + 1) * BATCH_SIZE, totalPage);

            log.info("爬取批次 {}/{}: 第 {} - {} 页", batch + 1, batchCount, startPage, endPage);

            // 提交批次内的所有页面爬取任务
            List<Future<List<AnnouncementMetaVo>>> futures = new ArrayList<>();
            for (int page = startPage; page <= endPage; page++) {
                final int pageNum = page;
                futures.add(crawlExecutor.submit(() -> 
                        announcementService.crawlPage(openId, pageNum)));
            }

            // 收集结果
            int successCount = 0;
            int failCount = 0;
            
            for (int i = 0; i < futures.size(); i++) {
                int pageNum = startPage + i;
                try {
                    List<AnnouncementMetaVo> pageResult = futures.get(i).get(30, TimeUnit.SECONDS);
                    if (pageResult != null && !pageResult.isEmpty()) {
                        allAnnouncements.addAll(pageResult);
                        successCount++;
                    }
                } catch (TimeoutException e) {
                    log.warn("第 {} 页爬取超时", pageNum);
                    failCount++;
                } catch (Exception e) {
                    log.warn("第 {} 页结果获取失败: {}", pageNum, e.getMessage());
                    failCount++;
                }
            }

            log.info("批次 {}/{} 完成 - 成功: {}, 失败: {}, 累计: {} 条",
                    batch + 1, batchCount, successCount, failCount, allAnnouncements.size());

            // 批次间休息，避免请求过快
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                log.error("初始化被中断");
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 按 ID 降序排序
        allAnnouncements.sort((a, b) -> Long.compare(
                Long.parseLong(b.getAnnouncementId()), Long.parseLong(a.getAnnouncementId())));

        return allAnnouncements;
    }

    /**
     * 保存到缓存
     */
    private void saveToCache(List<AnnouncementMetaVo> allAnnouncements) {
        log.info("开始保存 {} 条公告到 Redis...", allAnnouncements.size());

        // 分批保存，避免一次性写入过多
        int batchSize = 100;
        for (int i = 0; i < allAnnouncements.size(); i += batchSize) {
            int end = Math.min(i + batchSize, allAnnouncements.size());
            List<AnnouncementMetaVo> batch = allAnnouncements.subList(i, end);
            announcementCacheUtil.saveMetaBatch(batch);
            log.debug("已保存 {}/{} 条", end, allAnnouncements.size());
        }

        // 设置最新 ID
        if (!allAnnouncements.isEmpty()) {
            announcementCacheUtil.setLatestId(allAnnouncements.get(0).getAnnouncementId());
        }
    }
}
