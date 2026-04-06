package cn.edu.sztui.stream.application.service.impl;

import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.smarthttp.SmartCookieConverter;
import cn.edu.sztui.common.util.smarthttp.dto.SmartResponse;
import cn.edu.sztui.common.util.smarthttp.service.SmartHttpClient;
import cn.edu.sztui.common.util.smarthttp.service.SmartSession;
import cn.edu.sztui.stream.application.service.InfoService;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ContentParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfigLoader;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ParserFactory;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ParserStrategy;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 统一信息流服务实现
 * <p>
 * ⭐ 修复：crawlDetail 根据文章的 categoryCode 查找正确的 source，
 * 而不是永远取 channel.getSourceIds().get(0)。
 * 公文通每个分类的详情 URL 格式是 /info/{category}/{id}.htm，
 * 分类代码必须匹配，否则 404。
 */
@Slf4j
@Service
public class InfoServiceImpl implements InfoService {

    @Resource
    private InfoCacheUtil infoCacheUtil;

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    @Resource
    private CrawlerConfigLoader configLoader;

    @Resource
    private ParserFactory parserFactory;

    @Resource
    private SmartHttpClient smartHttpClient;

    // ==================== 频道与分类 ====================

    @Override
    public List<ChannelInfo> getChannelsWithUnread() {
        String userId = UserContext.getContext().getUserId();
        List<CrawlerConfig.ChannelConfig> channels = configLoader.getChannels();

        return channels.stream().map(ch -> {
            ChannelInfo info = new ChannelInfo();
            info.setId(ch.getId());
            info.setName(ch.getName());
            info.setIcon(ch.getIcon());
            info.setUnreadCount(infoCacheUtil.getUnreadCount(userId, ch.getId()));

            List<CategoryInfo> categories = new ArrayList<>();
            if (ch.getSourceIds() != null) {
                for (String sourceId : ch.getSourceIds()) {
                    CrawlerConfig.SourceConfig source = configLoader.findSourceById(sourceId);
                    if (source != null && source.getCategories() != null) {
                        source.getCategories().forEach((code, name) -> {
                            categories.add(new CategoryInfo(code, name));
                        });
                    }
                }
            }
            info.setCategories(categories);
            return info;
        }).collect(Collectors.toList());
    }

    @Override
    public Map<String, String> getCategoryCodeMap(String channelId) {
        CrawlerConfig.ChannelConfig channel = configLoader.findChannelById(channelId);
        if (channel == null || channel.getSourceIds() == null) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (String sourceId : channel.getSourceIds()) {
            CrawlerConfig.SourceConfig source = configLoader.findSourceById(sourceId);
            if (source != null && source.getCategories() != null) {
                result.putAll(source.getCategories());
            }
        }
        return result;
    }

    // ==================== 内容列表 ====================

    @Override
    public InfoListResult getList(String channelId, String categoryCode, int page, int pageSize) {
        List<ListParserResult.InfoItemMeta> list;
        Long total;

        if (StringUtils.hasText(categoryCode)) {
            list = infoCacheUtil.getListByCategory(channelId, categoryCode, page, pageSize);
            total = infoCacheUtil.getTotalCountByCategory(channelId, categoryCode);
        } else {
            list = infoCacheUtil.getList(channelId, page, pageSize);
            total = infoCacheUtil.getTotalCount(channelId);
        }

        InfoListResult result = new InfoListResult();
        result.setItems(list);
        result.setLatestId(infoCacheUtil.getLatestId(channelId));
        result.setTotal(total != null ? total : 0L);
        result.setHasMore(list.size() == pageSize);

        return result;
    }

    @Override
    public List<ListParserResult.InfoItemMeta> search(String keyword, String channelId, int limit) {
        if (StringUtils.hasText(channelId)) {
            return infoCacheUtil.searchByTitle(channelId, keyword, limit);
        }

        List<ListParserResult.InfoItemMeta> allResults = new ArrayList<>();
        for (CrawlerConfig.ChannelConfig ch : configLoader.getChannels()) {
            List<ListParserResult.InfoItemMeta> channelResults = infoCacheUtil.searchByTitle(ch.getId(), keyword, limit);
            allResults.addAll(channelResults);
        }

        return allResults.stream()
                .sorted((a, b) -> {
                    try {
                        return Long.compare(Long.parseLong(b.getId()), Long.parseLong(a.getId()));
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ==================== 内容详情 ====================

    @Override
    public ContentParserResult getDetail(String channelId, String id, String categoryCode) {
        // 1. 尝试从缓存获取
        ContentParserResult cached = infoCacheUtil.getContent(channelId, id);
        if (cached != null) {
            log.debug("命中详情缓存: channel={}, id={}", channelId, id);
            return cached;
        }

        // 2. 获取元数据以确定分类
        if (!StringUtils.hasText(categoryCode)) {
            ListParserResult.InfoItemMeta meta = infoCacheUtil.getMeta(channelId, id);
            if (meta != null) {
                categoryCode = meta.getCategoryCode();
                log.debug("从缓存元数据获取到分类: id={}, category={}", id, categoryCode);
            }
        }

        // 3. 爬取详情页
        ContentParserResult content = crawlDetail(channelId, id, categoryCode);

        // 4. 保存到缓存
        if (content != null && content.isSuccess()) {
            infoCacheUtil.saveContent(channelId, id, content);
        }

        return content;
    }

    /**
     * 爬取详情页
     * <p>
     * ⭐ 修复：根据 categoryCode 查找正确的 source，
     * 而不是永远用 channel.getSourceIds().get(0)。
     * 公文通的详情 URL 是 /info/{category}/{id}.htm，
     * 分类代码必须和文章实际分类匹配，否则 404。
     */
    private ContentParserResult crawlDetail(String channelId, String id, String categoryCode) {
        String userId = UserContext.getContext().getUserId();
        ProxySession session = authSessionCacheUtil.getSession(userId);
        if (session == null) {
            log.warn("无法获取用户会话: {}", userId);
            return ContentParserResult.fail("无法获取用户会话");
        }

        // ⭐ 修复：根据 categoryCode 找到正确的 source
        CrawlerConfig.SourceConfig source = findSourceForDetail(channelId, categoryCode);
        if (source == null) {
            log.warn("未找到匹配的数据源: channel={}, category={}", channelId, categoryCode);
            return ContentParserResult.fail("未找到匹配的数据源");
        }

        try {
            SmartSession smartSession = smartHttpClient.newSession(
                    SmartCookieConverter.jsonToSmartCookies(session.getCookiesJson()));

            // 构建详情 URL（使用正确 source 的 detailUrlTemplate）
            String url = buildDetailUrl(source, id, categoryCode);
            if (url == null) {
                log.error("无法构建详情 URL: source={}, id={}", source.getId(), id);
                return ContentParserResult.fail("无法构建详情 URL");
            }
            log.info("爬取详情: url={}", url);

            SmartResponse response = smartHttpClient.get(url, smartSession);
            if (!response.isSuccess()) {
                log.error("爬取详情失败: status={}, url={}", response.getStatusCode(), url);
                return ContentParserResult.fail("HTTP " + response.getStatusCode());
            }

            // 使用详情解析器
            ParserStrategy parser = parserFactory.getContentParser(source.getParserType());
            if (parser == null) {
                log.error("未找到详情解析器: type={}", source.getParserType());
                return ContentParserResult.fail("未找到解析器: " + source.getParserType());
            }

            ContentParserResult content = parser.parseContent(response.getBody(), source, id);
            if (content != null) {
                content.setId(id);
            }
            return content;

        } catch (Exception e) {
            log.error("爬取详情异常: channel={}, id={}, error={}", channelId, id, e.getMessage());
            return ContentParserResult.fail("爬取异常: " + e.getMessage());
        }
    }

    /**
     * ⭐ 新增：根据 categoryCode 在频道内查找正确的 source
     * <p>
     * 逻辑：
     * 1. 如果有 categoryCode，遍历频道下所有 source，找到 source.category == categoryCode 的
     * 2. 如果没有 categoryCode 或没找到匹配的，退回到第一个 source（兼容旧逻辑）
     * <p>
     * 例如：文章分类是 1019(科研)，就要用 gwt-keyan 的 detailUrlTemplate
     * → /info/1019/{id}.htm ✅
     * 而不是 gwt-jiaowu 的 /info/1018/{id}.htm ✗
     */
    private CrawlerConfig.SourceConfig findSourceForDetail(String channelId, String categoryCode) {
        CrawlerConfig.ChannelConfig channel = configLoader.findChannelById(channelId);
        if (channel == null || channel.getSourceIds() == null || channel.getSourceIds().isEmpty()) {
            return null;
        }

        // 优先按 categoryCode 精确匹配
        if (StringUtils.hasText(categoryCode)) {
            for (String sourceId : channel.getSourceIds()) {
                CrawlerConfig.SourceConfig source = configLoader.findSourceById(sourceId);
                if (source != null && categoryCode.equals(source.getCategoryCode())) {
                    log.debug("按分类匹配到数据源: category={} → source={}", categoryCode, source.getId());
                    return source;
                }
            }
            log.warn("未找到匹配分类 {} 的数据源，使用默认", categoryCode);
        }

        // Fallback：使用第一个 source
        String fallbackId = channel.getSourceIds().get(0);
        return configLoader.findSourceById(fallbackId);
    }

    /**
     * 构建详情 URL
     */
    private String buildDetailUrl(CrawlerConfig.SourceConfig source, String id, String categoryCode) {
        String template = source.getDetailUrl();
        if (template == null) {
            return null;
        }
        return template
                .replace("{id}", id)
                .replace("{category}", categoryCode != null ? categoryCode :
                        (source.getCategoryCode() != null ? source.getCategoryCode() : ""));
    }

    // ==================== 未读管理 ====================

    @Override
    public Map<String, Long> getUnreadCounts() {
        String userId = UserContext.getContext().getUserId();
        Map<String, Long> counts = new LinkedHashMap<>();

        for (CrawlerConfig.ChannelConfig ch : configLoader.getChannels()) {
            counts.put(ch.getId(), infoCacheUtil.getUnreadCount(userId, ch.getId()));
        }

        return counts;
    }

    @Override
    public void markChannelRead(String channelId, String latestId) {
        String userId = UserContext.getContext().getUserId();
        infoCacheUtil.setUserReadPosition(userId, channelId, latestId);
        log.debug("标记已读: userId={}, channel={}, latestId={}", userId, channelId, latestId);
    }
}