package cn.edu.sztui.stream.application.external.announcement;

import cn.edu.sztui.stream.application.external.AbstractCrawlTask;
import cn.edu.sztui.stream.infrastructure.persistence.entity.textDTO.AnnouncementMetaVo;
import cn.edu.sztui.stream.infrastructure.util.stream.StreamKeys;
import cn.edu.sztui.stream.infrastructure.util.stream.StreamPublisher;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 公告增量爬取定时任务
 * 
 * 文件位置：module-stream/src/main/java/cn/edu/sztui/stream/application/external/announcement/AnnouncementCrawlTask.java
 */
@Slf4j
@Component
public class AnnouncementCrawlTask extends AbstractCrawlTask {

    @Resource
    private AnnouncementService announcementService;

    @Resource
    private StreamPublisher streamPublisher;

    /**
     * 定时执行增量爬取
     * 
     * initialDelay: 启动后 1 分钟开始
     * fixedDelay: 每 10 分钟执行一次
     */
    @Scheduled(initialDelay = 60000, fixedDelay = 600000)
    public void scheduledCrawl() {
        log.info("开始执行公告增量爬取任务");
        execute();
        log.info("公告增量爬取任务执行完成");
    }

    @Override
    protected void doCrawl(String sourceOpenId) {
        List<AnnouncementMetaVo> newAnnouncements = announcementService.crawlIncremental(sourceOpenId);

        if (newAnnouncements.isEmpty()) {
            log.debug("无新公告");
            return;
        }

        log.info("发现 {} 条新公告", newAnnouncements.size());

        // 构建推送数据
        Map<String, Object> data = new HashMap<>();
        data.put("ids", newAnnouncements.stream().map(AnnouncementMetaVo::getAnnouncementId).toList());
        data.put("count", newAnnouncements.size());
        data.put("metas", newAnnouncements);
        data.put("latestId", newAnnouncements.get(0).getAnnouncementId());

        // 广播新公告通知
        streamPublisher.publishToAll(StreamKeys.TYPE_NEW_ANNOUNCEMENTS, data);
    }
}
