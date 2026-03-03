package cn.edu.sztui.stream.application.task.Impl;

import cn.edu.sztui.base.application.service.AnnouncementService;
import cn.edu.sztui.base.application.vo.AnnouncementMetaVo;
import cn.edu.sztui.base.infrastructure.util.cache.AnnouncementCacheUtil;
import cn.edu.sztui.stream.application.task.AbstractCrawlTask;
import cn.edu.sztui.stream.infrastructure.sse.dto.SseMessage;
import cn.edu.sztui.stream.infrastructure.stream.StreamKeys;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 公告爬取定时任务
 * <p>
 * 每 10 分钟爬取一次学校公文通首页，检查是否有新公告
 */
@Slf4j
@Component
public class AnnouncementCrawlTask extends AbstractCrawlTask {

    @Resource
    private AnnouncementService announcementService;

    @Resource
    private AnnouncementCacheUtil announcementCacheUtil;

    @Override
    protected String getTaskName() {
        return "公告爬取";
    }

    @Override
    protected String getTopicName() {
        return "announcement";
    }

    /**
     * 定时执行爬取任务
     * <p>
     * 每 10 分钟执行一次
     */
    @Scheduled(fixedRate = 10 * 60 * 1000, initialDelay = 60 * 1000)
    public void scheduledCrawl() {
        executeCrawl();
    }

    /**
     * 手动触发爬取（供管理接口调用）
     */
    public void manualCrawl() {
        executeCrawl();
    }

    @Override
    protected void doCrawl(String sourceOpenId) {
        // 1. 增量爬取
        List<AnnouncementMetaVo> newAnnouncements =
                announcementService.crawlIncremental(sourceOpenId);

        if (newAnnouncements.isEmpty()) {
            log.debug("无新公告");
            return;
        }

        log.info("发现 {} 条新公告", newAnnouncements.size());

        // 2. 广播新公告（即使没有订阅者也需要更新缓存，上面已更新）
        if (hasSubscribers()) {
            broadcastNewAnnouncements(newAnnouncements);
        } else {
            log.debug("无订阅者，跳过广播");
        }
    }

    /**
     * 广播新公告通知
     */
    private void broadcastNewAnnouncements(List<AnnouncementMetaVo> newAnnouncements) {
        // 提取新公告 ID 列表
        List<String> newIds = newAnnouncements.stream()
                .map(AnnouncementMetaVo::getId)
                .collect(Collectors.toList());

        // 构建消息数据
        Map<String, Object> data = new HashMap<>();
        data.put("ids", newIds);
        data.put("count", newAnnouncements.size());
        data.put("metas", newAnnouncements);
        data.put("latestId", announcementCacheUtil.getLatestId());

        // 创建 SSE 消息
        SseMessage<Map<String, Object>> message = SseMessage.data(
            StreamKeys.TYPE_NEW_ANNOUNCEMENTS,
            data
        );

        // 通过 Stream 发布消息（StreamConsumer 会消费并广播）
        streamPublisher.publishAnnouncement(message);

        log.info("已发布 {} 条新公告消息到 Stream，订阅者数量: {}",
                newAnnouncements.size(),
                sseEmitterManager.getConnectionCount(getTopicName()));
    }
}
