package cn.edu.sztui.stream.application.external.announcement;

import cn.edu.sztui.base.application.service.AnnouncementService;
import cn.edu.sztui.base.application.vo.AnnouncementMetaVo;
import cn.edu.sztui.stream.application.external.AbstractCrawlTask;
import cn.edu.sztui.stream.infrastructure.stream.StreamKeys;
import cn.edu.sztui.stream.infrastructure.stream.StreamPublisher;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 公告增量爬取定时任务
 */
@Slf4j
@Component
public class AnnouncementCrawlTask extends AbstractCrawlTask {

    @Resource
    // @Qualifier("${announcement.service.impl:announcementService}")
    @Qualifier("announcementServiceV2")
    private AnnouncementService announcementService;

    @Resource
    private StreamPublisher streamPublisher;

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

        Map<String, Object> data = new HashMap<>();
        data.put("ids", newAnnouncements.stream().map(AnnouncementMetaVo::getId).toList());
        data.put("count", newAnnouncements.size());
        data.put("metas", newAnnouncements);
        data.put("latestId", newAnnouncements.get(0).getId());

        streamPublisher.publishToAll(StreamKeys.TYPE_NEW_ANNOUNCEMENTS, data);
    }
}