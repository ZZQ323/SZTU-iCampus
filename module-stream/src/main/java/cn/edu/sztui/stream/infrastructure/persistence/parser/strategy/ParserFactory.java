package cn.edu.sztui.stream.infrastructure.persistence.parser.strategy;

import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.SourceConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl.SztuGwtContentParser;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl.SztuGwtListParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析器工厂
 * <p>
 * 根据数据源配置中的 parserType 获取对应的解析器实现
 */
@Slf4j
@Component
public class ParserFactory {

    /** 列表解析器 Map: parserType -> ListParser */
    private final Map<String, ParserStrategy> listParsers = new HashMap<>();

    /** 详情解析器 Map: parserType -> ContentParser */
    private final Map<String, ParserStrategy> contentParsers = new HashMap<>();

    @Resource
    private List<ParserStrategy> allParsers;

    @PostConstruct
    public void init() {
        // 自动注册所有解析器
        if (allParsers != null) {
            for (ParserStrategy parser : allParsers) {
                registerParser(parser);
            }
        }

        // 如果没有通过 Spring 注入，手动注册默认解析器
        if (listParsers.isEmpty()) {
            registerParser(new SztuGwtListParser());
            registerParser(new SztuGwtContentParser());
        }

        log.info("解析器工厂初始化完成 - 列表解析器: {}, 详情解析器: {}",
                listParsers.size(), contentParsers.size());
    }

    /**
     * 注册解析器
     */
    public void registerParser(ParserStrategy parser) {
        String type = parser.getType();

        // 根据类名判断是列表解析器还是详情解析器
        String className = parser.getClass().getSimpleName().toLowerCase();
        if (className.contains("list")) {
            listParsers.put(type, parser);
            log.debug("注册列表解析器: {} -> {}", type, parser.getClass().getSimpleName());
        } else if (className.contains("content") || className.contains("detail")) {
            contentParsers.put(type, parser);
            log.debug("注册详情解析器: {} -> {}", type, parser.getClass().getSimpleName());
        } else {
            // 默认作为列表解析器
            listParsers.put(type, parser);
            log.debug("注册解析器（默认列表）: {} -> {}", type, parser.getClass().getSimpleName());
        }
    }

    /**
     * 获取列表解析器
     *
     * @param parserType 解析器类型
     * @return 解析器实例，如果不存在返回 null
     */
    public ParserStrategy getListParser(String parserType) {
        return listParsers.get(parserType);
    }

    /**
     * 获取详情解析器
     *
     * @param parserType 解析器类型
     * @return 解析器实例，如果不存在返回 null
     */
    public ParserStrategy getContentParser(String parserType) {
        return contentParsers.get(parserType);
    }

    /**
     * 根据数据源配置获取列表解析器
     */
    public ParserStrategy getListParser(SourceConfig sourceConfig) {
        if (sourceConfig == null || sourceConfig.getParserType() == null) {
            return null;
        }
        return getListParser(sourceConfig.getParserType());
    }

    /**
     * 根据数据源配置获取详情解析器
     */
    public ParserStrategy getContentParser(SourceConfig sourceConfig) {
        if (sourceConfig == null || sourceConfig.getParserType() == null) {
            return null;
        }
        return getContentParser(sourceConfig.getParserType());
    }

    /**
     * 检查是否支持指定的解析器类型
     */
    public boolean isSupported(String parserType) {
        return listParsers.containsKey(parserType) || contentParsers.containsKey(parserType);
    }

    /**
     * 获取所有支持的解析器类型
     */
    public java.util.Set<String> getSupportedTypes() {
        java.util.Set<String> types = new java.util.HashSet<>();
        types.addAll(listParsers.keySet());
        types.addAll(contentParsers.keySet());
        return types;
    }
}
