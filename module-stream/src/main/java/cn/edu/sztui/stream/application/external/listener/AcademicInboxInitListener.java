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
        int okCount = 0;
        int failCount = 0;
        int authExpiredCount = 0;

        for (CrawlerConfig.SourceConfig source : sources) {
            if (!"acdm-inbox".equals(source.getParserType())) {
                continue;
            }

            try {
                // 用 crawlIncremental 而非 initSource：
                // initSource 有"已初始化就早退"的守卫，会被之前运行遗留的 initialized=true 标记短路；
                // crawlIncremental 语义上也更合适 —— 每次 cookies 新就绪时重新拉一次首页即可。
                cn.edu.sztui.stream.application.external.engine.CrawlResult result =
                        crawlEngine.crawlIncremental(source.getId(), userId);
                if (result.isAuthError()) {
                    authExpiredCount++;
                    log.warn("教务内网首次爬取登录页响应: source={}, userId={}", source.getId(), userId);
                } else if (result.isSuccess()) {
                    okCount++;
                    log.info("教务内网首次爬取完成: source={}, 新增 {} 条", source.getId(), result.getNewCount());
                } else {
                    failCount++;
                    log.warn("教务内网首次爬取失败: source={}, error={}", source.getId(), result.getErrorMessage());
                }
            } catch (Exception e) {
                failCount++;
                log.error("教务内网源初始化异常: {}, error={}", source.getId(), e.getMessage());
            }
        }

        log.info("教务内网数据源初始化完成: userId={}, 成功 {} 个, 登录页 {} 个, 失败 {} 个",
                userId, okCount, authExpiredCount, failCount);
    }
}
