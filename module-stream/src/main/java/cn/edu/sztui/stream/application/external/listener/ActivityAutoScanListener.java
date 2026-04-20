package cn.edu.sztui.stream.application.external.listener;

import cn.edu.sztui.stream.application.activity.service.ActivityScanService;
import cn.edu.sztui.stream.application.external.event.ArticleSavedEvent;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文章保存事件 → 活动抽取的自动化监听器。
 * <p>
 * 通过 {@code @ConditionalOnProperty} 整个 bean 可 opt-in：
 * <pre>
 * ai.activity.auto-process: true   # 打开自动化
 * </pre>
 * 默认关闭（false），此时 bean 不会被注册，爬虫发布的事件无人消费 → 零开销。
 * <p>
 * 开启后策略：
 * <ul>
 *   <li>只处理 {@code ai.activity.default-channels} 列表里的频道（避免在报道类频道烧 token）</li>
 *   <li>调用 {@link ActivityScanService#autoProcess}，走规则预筛 → LLM 缓存 → 索引</li>
 *   <li>异常不中断爬虫主流程（@Async 异步 + try-catch 在 scanService 内部）</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(value = "ai.activity.auto-process", havingValue = "true")
public class ActivityAutoScanListener {

    @Resource
    private ActivityScanService scanService;

    @Value("${ai.activity.default-channels:announcement,job}")
    private List<String> defaultChannels;

    @Async
    @EventListener
    public void onArticleSaved(ArticleSavedEvent event) {
        ListParserResult.InfoItemMeta meta = event.getMeta();
        if (meta == null || meta.getChannelId() == null) return;

        // 频道白名单 —— 报道类频道跳过，避免烧 token 抓事后报道
        if (!defaultChannels.contains(meta.getChannelId())) return;

        log.debug("[activity-auto] processing: channel={} id={} title={}",
                meta.getChannelId(), meta.getId(), meta.getTitle());
        scanService.autoProcess(meta);
    }
}
