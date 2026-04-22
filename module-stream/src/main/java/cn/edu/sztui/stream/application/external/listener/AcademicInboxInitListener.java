package cn.edu.sztui.stream.application.external.listener;

import cn.edu.sztui.base.domain.event.AcademicSessionReadyEvent;
import cn.edu.sztui.stream.application.external.engine.CrawlEngine;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfigLoader;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 教务会话就绪事件监听器
 * <p>
 * 时序背景：
 * <ul>
 *   <li>{@code UserLoginEvent} 发布时，只有 WebVPN 网关 cookies，没有 jwxt 子域 cookies</li>
 *   <li>前端登录流程紧接着调 {@code /acdm/v1/init}，
 *       {@link cn.edu.sztui.base.application.service.AcademicService#init()} 跑完重定向链后把 jwxt cookies 存 Redis，
 *       然后发布 {@link AcademicSessionReadyEvent}</li>
 *   <li>本监听器据此对该 userId 的 acdm-* 数据源做首次爬取</li>
 * </ul>
 * <p>
 * 只负责 "cookies 新就绪时拉一次" 这个场景。稳态轮询交给
 * {@code AcademicInboxFastScheduler}（Step E）；session 过期自愈在 scheduler 内部处理，
 * 那条路径不会再发事件，避免回环。
 */
@Slf4j
@Component
public class AcademicInboxInitListener {

    @Resource
    private CrawlEngine crawlEngine;

    @Resource
    private CrawlerConfigLoader configLoader;

    @Async
    @EventListener
    public void onAcademicSessionReady(AcademicSessionReadyEvent event) {
        String userId = event.getUserId();
        log.info("收到教务会话就绪事件: userId={}", userId);

        List<CrawlerConfig.SourceConfig> sources = configLoader.getEnabledSources();
        int initCount = 0;
        int failCount = 0;

        for (CrawlerConfig.SourceConfig source : sources) {
            if (!"acdm-inbox".equals(source.getParserType())) {
                continue;
            }

            try {
                crawlEngine.initSource(source.getId(), userId);
                initCount++;
            } catch (Exception e) {
                log.error("教务内网源初始化失败: {}, error={}", source.getId(), e.getMessage());
                failCount++;
            }
        }

        log.info("教务内网数据源初始化完成: userId={}, 初始化 {} 个, 失败 {} 个",
                userId, initCount, failCount);
    }
}
