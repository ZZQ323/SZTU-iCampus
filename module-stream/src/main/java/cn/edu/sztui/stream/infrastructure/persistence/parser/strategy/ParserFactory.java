package cn.edu.sztui.stream.infrastructure.persistence.parser.strategy;

import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.SourceConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import java.util.*;

/**
 * 解析器工厂
 * <p>
 * 双 Map 设计：listParsers + contentParsers
 * <p>
 * SztuGwtListParser 和 SztuGwtContentParser 的 getType() 都返回 "sztu-gwt"，
 * 但一个是列表解析器，一个是详情解析器，用类名中的关键字区分。
 */
@Slf4j
@Component
public class ParserFactory {

    /**
     * 列表解析器 Map: parserType -> Parser
     */
    private final Map<String, ParserStrategy> listParsers = new HashMap<>();

    /**
     * 详情解析器 Map: parserType -> Parser
     */
    private final Map<String, ParserStrategy> contentParsers = new HashMap<>();

    @Resource
    private List<ParserStrategy> allParsers;

    @PostConstruct
    public void init() {
        if (allParsers != null) {
            for (ParserStrategy parser : allParsers) {
                registerParser(parser);
            }
        }
        log.info("解析器工厂初始化完成 - 列表解析器: {}, 详情解析器: {}",
                listParsers.size(), contentParsers.size());
    }

    /**
     * 按类名关键字分流注册
     */
    private void registerParser(ParserStrategy parser) {
        String type = parser.getType();
        String className = parser.getClass().getSimpleName().toLowerCase();

        if (className.contains("list")) {
            listParsers.put(type, parser);
            log.debug("注册列表解析器: {} -> {}", type, parser.getClass().getSimpleName());
        } else if (className.contains("content") || className.contains("detail")) {
            contentParsers.put(type, parser);
            log.debug("注册详情解析器: {} -> {}", type, parser.getClass().getSimpleName());
        } else {
            // 默认两边都注册
            listParsers.put(type, parser);
            contentParsers.put(type, parser);
            log.debug("注册通用解析器: {} -> {}", type, parser.getClass().getSimpleName());
        }
    }

    // ==================== 获取解析器 ====================

    public ParserStrategy getListParser(String parserType) {
        return listParsers.get(parserType);
    }

    public ParserStrategy getContentParser(String parserType) {
        return contentParsers.get(parserType);
    }

    public ParserStrategy getListParser(SourceConfig sourceConfig) {
        return sourceConfig != null ? listParsers.get(sourceConfig.getParserType()) : null;
    }

    public ParserStrategy getContentParser(SourceConfig sourceConfig) {
        return sourceConfig != null ? contentParsers.get(sourceConfig.getParserType()) : null;
    }

    // ==================== CrawlEngine 调用的便捷方法 ====================

    /**
     * 解析列表页
     */
    public ListParserResult parseList(String parserType, String html, SourceConfig sourceConfig, int page) {
        ParserStrategy parser = listParsers.get(parserType);
        if (parser == null) {
            log.error("未找到列表解析器: {}", parserType);
            return null;
        }
        return parser.parseList(html, sourceConfig, page);
    }

    /**
     * 解析详情页
     */
    public ContentParserResult parseContent(String parserType, String html, SourceConfig sourceConfig, String itemId) {
        ParserStrategy parser = contentParsers.get(parserType);
        if (parser == null) {
            log.error("未找到详情解析器: {}", parserType);
            return null;
        }
        return parser.parseContent(html, sourceConfig, itemId);
    }

    // ==================== 查询方法 ====================

    public boolean isSupported(String parserType) {
        return listParsers.containsKey(parserType) || contentParsers.containsKey(parserType);
    }

    public Set<String> getSupportedTypes() {
        Set<String> types = new HashSet<>();
        types.addAll(listParsers.keySet());
        types.addAll(contentParsers.keySet());
        return types;
    }
}