package cn.edu.sztui.stream.application.external.event;

import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 新文章被爬取并保存后发布的事件。
 * <p>
 * 在 {@code CrawlEngine.saveMetaBatch} 完成后**按文章逐条**发布。
 * 下游监听器（如 {@code ActivityAutoScanListener}）可订阅此事件触发后续处理，
 * 如 LLM 活动抽取、WebSocket 推送等。
 * <p>
 * 设计原则（见 .claude/CLAUDE.md 关键设计原则 3）：**Spring 事件解耦**而非直接依赖，
 * 避免 module-base 与下游模块的循环依赖。
 */
@Getter
public class ArticleSavedEvent extends ApplicationEvent {

    /** 保存的文章元数据（含 channelId / sourceId / url 等） */
    private final ListParserResult.InfoItemMeta meta;

    public ArticleSavedEvent(Object source, ListParserResult.InfoItemMeta meta) {
        super(source);
        this.meta = meta;
    }
}
