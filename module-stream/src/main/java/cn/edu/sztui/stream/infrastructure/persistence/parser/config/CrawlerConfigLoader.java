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
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CrawlerConfigLoader {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

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

    private void loadUrls() {
        try {
            ClassPathResource resource = new ClassPathResource("crawler/urls.yml");
            if (!resource.exists()) {
                log.info("urls.yml 不存在，跳过 URL 变量加载");
                return;
            }

            Yaml yaml = new Yaml();
            try (InputStream is = resource.getInputStream()) {
                Map<String, Object> root = yaml.load(is);
                if (root != null) {
                    flattenMap("", root, urlVars);
                }
            }
            log.info("加载 URL 变量: {} 个", urlVars.size());
        } catch (Exception e) {
            log.error("加载 urls.yml 失败", e);
        }
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

    private void loadSources() {
        try {
            ClassPathResource resource = new ClassPathResource("crawler/sources.yml");
            if (!resource.exists()) {
                log.warn("sources.yml 不存在，跳过加载");
                return;
            }

            String yamlContent;
            try (InputStream is = resource.getInputStream()) {
                yamlContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            yamlContent = resolvePlaceholders(yamlContent);

            LoaderOptions options = new LoaderOptions();
            Yaml yaml = new Yaml(new Constructor(SourcesRoot.class, options));
            SourcesRoot root = yaml.load(yamlContent);

            if (root != null && root.getSources() != null) {
                for (SourceConfig source : root.getSources()) {
                    if (source.isEnabled()) {
                        sourceMap.put(source.getId(), source);
                        log.debug("加载数据源: {} - {} (parser: {})",
                                source.getId(), source.getName(), source.getParserType());
                    }
                }
            }
        } catch (Exception e) {
            log.error("加载 sources.yml 失败", e);
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
