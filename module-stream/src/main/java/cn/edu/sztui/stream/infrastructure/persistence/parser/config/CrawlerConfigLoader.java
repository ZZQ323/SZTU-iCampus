package cn.edu.sztui.stream.infrastructure.persistence.parser.config;

import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

import jakarta.annotation.PostConstruct;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 爬虫配置加载器
 * <p>
 * 从 classpath:crawler/ 目录加载 YAML 配置文件
 */
@Slf4j
@Component
public class CrawlerConfigLoader {

    /**
     * 频道配置 Map: channelId -> ChannelConfig
     */
    @Getter
    private Map<String, ChannelConfig> channelMap = new HashMap<>();

    /**
     * 数据源配置 Map: sourceId -> SourceConfig
     */
    @Getter
    private Map<String, SourceConfig> sourceMap = new HashMap<>();

    /**
     * 按频道分组的数据源 Map: channelId -> List<SourceConfig>
     */
    @Getter
    private Map<String, List<SourceConfig>> sourcesByChannel = new HashMap<>();

    /**
     * 按解析器类型分组的数据源 Map: parserType -> List<SourceConfig>
     */
    @Getter
    private Map<String, List<SourceConfig>> sourcesByParser = new HashMap<>();

    @PostConstruct
    public void init() {
        loadChannels();
        loadSources();
        buildIndexes();
        log.info("爬虫配置加载完成 - 频道: {} 个, 数据源: {} 个", channelMap.size(), sourceMap.size());
    }

    /**
     * 加载频道配置
     */
    private void loadChannels() {
        try {
            ClassPathResource resource = new ClassPathResource("crawler/channels.yml");
            if (!resource.exists()) {
                log.warn("channels.yml 不存在，跳过加载");
                return;
            }

            LoaderOptions options = new LoaderOptions();
            Yaml yaml = new Yaml(new Constructor(ChannelsRoot.class, options));

            try (InputStream is = resource.getInputStream()) {
                ChannelsRoot root = yaml.load(is);
                if (root != null && root.getChannels() != null) {
                    for (ChannelConfig channel : root.getChannels()) {
                        if (channel.getEnabled() == null || channel.getEnabled()) {
                            channelMap.put(channel.getId(), channel);
                            log.debug("加载频道: {} - {}", channel.getId(), channel.getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("加载 channels.yml 失败", e);
        }
    }

    /**
     * 加载数据源配置
     */
    private void loadSources() {
        try {
            ClassPathResource resource = new ClassPathResource("crawler/sources.yml");
            if (!resource.exists()) {
                log.warn("sources.yml 不存在，跳过加载");
                return;
            }

            LoaderOptions options = new LoaderOptions();
            Yaml yaml = new Yaml(new Constructor(SourcesRoot.class, options));

            try (InputStream is = resource.getInputStream()) {
                SourcesRoot root = yaml.load(is);
                if (root != null && root.getSources() != null) {
                    for (SourceConfig source : root.getSources()) {
                        if (source.isEnabled()) {
                            sourceMap.put(source.getId(), source);
                            log.debug("加载数据源: {} - {} (parser: {})",
                                    source.getId(), source.getName(), source.getParserType());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("加载 sources.yml 失败", e);
        }
    }

    /**
     * 构建索引
     */
    private void buildIndexes() {
        // 按频道分组
        sourcesByChannel = sourceMap.values().stream()
                .filter(s -> s.getChannelId() != null)
                .collect(Collectors.groupingBy(SourceConfig::getChannelId));

        // 按解析器类型分组
        sourcesByParser = sourceMap.values().stream()
                .filter(s -> s.getParserType() != null)
                .collect(Collectors.groupingBy(SourceConfig::getParserType));
    }

    // ==================== 查询方法 ====================

    /**
     * 获取频道配置
     */
    public ChannelConfig getChannel(String channelId) {
        return channelMap.get(channelId);
    }

    /**
     * 获取数据源配置
     */
    public SourceConfig getSource(String sourceId) {
        return sourceMap.get(sourceId);
    }

    /**
     * 获取频道下的所有数据源
     */
    public List<SourceConfig> getSourcesByChannel(String channelId) {
        return sourcesByChannel.getOrDefault(channelId, Collections.emptyList());
    }

    /**
     * 获取使用指定解析器的所有数据源
     */
    public List<SourceConfig> getSourcesByParser(String parserType) {
        return sourcesByParser.getOrDefault(parserType, Collections.emptyList());
    }

    /**
     * 获取所有启用的频道
     */
    public List<ChannelConfig> getEnabledChannels() {
        return channelMap.values().stream()
                .filter(c -> c.getEnabled() == null || c.getEnabled())
                .sorted(Comparator.comparingInt(c -> c.getSort() != null ? c.getSort() : 999))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有启用的数据源
     */
    public List<SourceConfig> getEnabledSources() {
        return sourceMap.values().stream()
                .filter(SourceConfig::isEnabled)
                .sorted(Comparator.comparingInt(s -> s.getSort() != null ? s.getSort() : 999))
                .collect(Collectors.toList());
    }

    /**
     * 重新加载配置
     */
    public synchronized void reload() {
        channelMap.clear();
        sourceMap.clear();
        sourcesByChannel.clear();
        sourcesByParser.clear();
        init();
    }

    /**
     * 别名：findChannelById → getChannel
     */
    public ChannelConfig findChannelById(String channelId) {
        return getChannel(channelId);
    }

    /**
     * 别名：findSourceById → getSource
     */
    public SourceConfig findSourceById(String sourceId) {
        return getSource(sourceId);
    }

    /**
     * 别名：getChannels → getEnabledChannels（InfoController/InfoServiceImpl 调用）
     */
    public List<ChannelConfig> getChannels() {
        return getEnabledChannels();
    }

    /**
     * 别名：getSources → getEnabledSources（InfoController 调用）
     */
    public List<SourceConfig> getSources() {
        return getEnabledSources();
    }

    /**
     * 获取分类树（InfoController 调用）
     */
    public Map<String, Object> getCategoryTree() {
        Map<String, Object> tree = new HashMap<>();
        List<Map<String, Object>> channelList = new ArrayList<>();

        for (ChannelConfig channel : getEnabledChannels()) {
            Map<String, Object> chMap = new HashMap<>();
            chMap.put("id", channel.getId());
            chMap.put("name", channel.getName());
            chMap.put("icon", channel.getIcon());

            // 收集该频道下所有源的分类
            List<Map<String, String>> categories = new ArrayList<>();
            List<SourceConfig> sources = getSourcesByChannel(channel.getId());
            for (SourceConfig source : sources) {
                if (source.getCategory() != null && source.getCategoryName() != null) {
                    Map<String, String> cat = new HashMap<>();
                    cat.put("code", source.getCategory());
                    cat.put("name", source.getCategoryName());
                    categories.add(cat);
                }
            }
            chMap.put("categories", categories);
            channelList.add(chMap);
        }

        tree.put("channels", channelList);
        return tree;
    }
}
