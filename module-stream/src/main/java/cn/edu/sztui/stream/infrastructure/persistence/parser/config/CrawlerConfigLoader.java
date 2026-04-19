package cn.edu.sztui.stream.infrastructure.persistence.parser.config;

import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

import jakarta.annotation.PostConstruct;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CrawlerConfigLoader {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private static final String URLS_PATTERN = "classpath:crawler/*/*-urls.yml";
    private static final String CHANNELS_PATTERN = "classpath:crawler/*/*-channels.yml";
    private static final String SOURCES_PATTERN = "classpath:crawler/*/*-sources.yml";

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Getter
    private Map<String, ChannelConfig> channelMap = new HashMap<>();

    @Getter
    private Map<String, SourceConfig> sourceMap = new HashMap<>();

    @Getter
    private Map<String, List<SourceConfig>> sourcesByChannel = new HashMap<>();

    @Getter
    private Map<String, List<SourceConfig>> sourcesByParser = new HashMap<>();

    private Map<String, String> urlVars = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        loadUrls();
        loadChannels();
        loadSources();
        buildIndexes();
        log.info("爬虫配置加载完成 - 频道: {} 个, 数据源: {} 个, URL变量: {} 个",
                channelMap.size(), sourceMap.size(), urlVars.size());
    }

    // ==================== URL 变量加载 ====================

    private Resource[] scan(String pattern) {
        try {
            Resource[] resources = resolver.getResources(pattern);
            Arrays.sort(resources, Comparator.comparing(r -> {
                try {
                    return r.getURL().toString();
                } catch (Exception e) {
                    return "";
                }
            }));
            return resources;
        } catch (Exception e) {
            log.error("扫描资源失败: {}", pattern, e);
            return new Resource[0];
        }
    }

    private void loadUrls() {
        Resource[] resources = scan(URLS_PATTERN);
        if (resources.length == 0) {
            log.warn("未找到任何 *-urls.yml，跳过 URL 变量加载");
            return;
        }
        Yaml yaml = new Yaml();
        for (Resource resource : resources) {
            try (InputStream is = resource.getInputStream()) {
                Map<String, Object> root = yaml.load(is);
                if (root != null) {
                    flattenMap("", root, urlVars);
                }
                log.debug("加载 URL 文件: {}", resource.getFilename());
            } catch (Exception e) {
                log.error("加载 URL 文件失败: {}", resource.getFilename(), e);
            }
        }
        log.info("加载 URL 变量: {} 个（来自 {} 个文件）", urlVars.size(), resources.length);
    }

    @SuppressWarnings("unchecked")
    private void flattenMap(String prefix, Map<String, Object> map, Map<String, String> result) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flattenMap(key, (Map<String, Object>) value, result);
            } else if (value != null) {
                result.put(key, value.toString());
            }
        }
    }

    private String resolvePlaceholders(String text) {
        if (text == null || urlVars.isEmpty()) return text;
        Matcher matcher = PLACEHOLDER.matcher(text);
        if (!matcher.find()) return text;

        StringBuilder sb = new StringBuilder();
        matcher.reset();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = urlVars.get(key);
            if (value != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
            } else {
                log.warn("未找到 URL 变量: {}", key);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // ==================== 配置加载 ====================

    private void loadChannels() {
        Resource[] resources = scan(CHANNELS_PATTERN);
        if (resources.length == 0) {
            log.warn("未找到任何 *-channels.yml，跳过频道加载");
            return;
        }
        LoaderOptions options = new LoaderOptions();
        for (Resource resource : resources) {
            try (InputStream is = resource.getInputStream()) {
                Yaml yaml = new Yaml(new Constructor(ChannelsRoot.class, options));
                ChannelsRoot root = yaml.load(is);
                if (root != null && root.getChannels() != null) {
                    for (ChannelConfig channel : root.getChannels()) {
                        if (channel.getEnabled() == null || channel.getEnabled()) {
                            ChannelConfig previous = channelMap.put(channel.getId(), channel);
                            if (previous != null) {
                                log.warn("频道 id 冲突被覆盖: {} (来自 {})", channel.getId(), resource.getFilename());
                            } else {
                                log.debug("加载频道: {} - {} (来自 {})",
                                        channel.getId(), channel.getName(), resource.getFilename());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("加载频道文件失败: {}", resource.getFilename(), e);
            }
        }
    }

    private void loadSources() {
        Resource[] resources = scan(SOURCES_PATTERN);
        if (resources.length == 0) {
            log.warn("未找到任何 *-sources.yml，跳过数据源加载");
            return;
        }
        LoaderOptions options = new LoaderOptions();
        for (Resource resource : resources) {
            try {
                String yamlContent;
                try (InputStream is = resource.getInputStream()) {
                    yamlContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
                yamlContent = resolvePlaceholders(yamlContent);

                Yaml yaml = new Yaml(new Constructor(SourcesRoot.class, options));
                SourcesRoot root = yaml.load(yamlContent);

                if (root != null && root.getSources() != null) {
                    for (SourceConfig source : root.getSources()) {
                        if (source.isEnabled()) {
                            SourceConfig previous = sourceMap.put(source.getId(), source);
                            if (previous != null) {
                                log.warn("数据源 id 冲突被覆盖: {} (来自 {})", source.getId(), resource.getFilename());
                            } else {
                                log.debug("加载数据源: {} - {} (parser: {}, 来自 {})",
                                        source.getId(), source.getName(), source.getParserType(), resource.getFilename());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("加载数据源文件失败: {}", resource.getFilename(), e);
            }
        }
    }

    private void buildIndexes() {
        sourcesByChannel = sourceMap.values().stream()
                .filter(s -> s.getChannelId() != null)
                .collect(Collectors.groupingBy(SourceConfig::getChannelId));

        sourcesByParser = sourceMap.values().stream()
                .filter(s -> s.getParserType() != null)
                .collect(Collectors.groupingBy(SourceConfig::getParserType));
    }

    // ==================== 查询方法 ====================

    public ChannelConfig getChannel(String channelId) {
        return channelMap.get(channelId);
    }

    public SourceConfig getSource(String sourceId) {
        return sourceMap.get(sourceId);
    }

    public List<SourceConfig> getSourcesByChannel(String channelId) {
        return sourcesByChannel.getOrDefault(channelId, Collections.emptyList());
    }

    public List<SourceConfig> getSourcesByParser(String parserType) {
        return sourcesByParser.getOrDefault(parserType, Collections.emptyList());
    }

    public List<ChannelConfig> getEnabledChannels() {
        return channelMap.values().stream()
                .filter(c -> c.getEnabled() == null || c.getEnabled())
                .sorted(Comparator.comparingInt(c -> c.getSort() != null ? c.getSort() : 999))
                .collect(Collectors.toList());
    }

    public List<SourceConfig> getEnabledSources() {
        return sourceMap.values().stream()
                .filter(SourceConfig::isEnabled)
                .sorted(Comparator.comparingInt(s -> s.getSort() != null ? s.getSort() : 999))
                .collect(Collectors.toList());
    }

    public synchronized void reload() {
        channelMap.clear();
        sourceMap.clear();
        sourcesByChannel.clear();
        sourcesByParser.clear();
        urlVars.clear();
        init();
    }

    public ChannelConfig findChannelById(String channelId) {
        return getChannel(channelId);
    }

    public SourceConfig findSourceById(String sourceId) {
        return getSource(sourceId);
    }

    public List<ChannelConfig> getChannels() {
        return getEnabledChannels();
    }

    public List<SourceConfig> getSources() {
        return getEnabledSources();
    }

    public Map<String, Object> getCategoryTree() {
        Map<String, Object> tree = new HashMap<>();
        List<Map<String, Object>> channelList = new ArrayList<>();

        for (ChannelConfig channel : getEnabledChannels()) {
            Map<String, Object> chMap = new HashMap<>();
            chMap.put("id", channel.getId());
            chMap.put("name", channel.getName());
            chMap.put("icon", channel.getIcon());

            List<Map<String, String>> categories = new ArrayList<>();
            List<SourceConfig> sources = getSourcesByChannel(channel.getId());
            for (SourceConfig source : sources) {
                if (source.getCategoryCode() != null && source.getCategoryName() != null) {
                    Map<String, String> cat = new HashMap<>();
                    cat.put("code", source.getCategoryCode());
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
