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
        String openId = UserContext.getContext().getOpenId();
        List<CrawlerConfig.ChannelConfig> channels = configLoader.getChannels();

        return channels.stream().map(ch -> {
            ChannelInfo info = new ChannelInfo();
            info.setId(ch.getId());
            info.setName(ch.getName());
            info.setIcon(ch.getIcon());
            info.setUnreadCount(infoCacheUtil.getUnreadCount(openId, ch.getId()));

            // 获取频道下的分类
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

        // 搜索所有频道
        List<ListParserResult.InfoItemMeta> allResults = new ArrayList<>();
        for (CrawlerConfig.ChannelConfig ch : configLoader.getChannels()) {
            List<ListParserResult.InfoItemMeta> channelResults = infoCacheUtil.searchByTitle(ch.getId(), keyword, limit);
            allResults.addAll(channelResults);
        }

        // 按 ID 降序排序，取前 limit 条
        return allResults.stream()
                .sorted((a, b) -> Long.compare(Long.parseLong(b.getId()), Long.parseLong(a.getId())))
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
                categoryCode = meta.getCategory();
            }
        }

        // 3. 爬取详情页
        ContentParserResult content = crawlDetail(channelId, id, categoryCode);

        // 4. 保存到缓存
        if (content != null) {
            infoCacheUtil.saveContent(channelId, id, content);
        }

        return content;
    }

    private ContentParserResult crawlDetail(String channelId, String id, String categoryCode) {
        String openId = UserContext.getContext().getOpenId();
        ProxySession session = authSessionCacheUtil.getSession(openId);
        if (session == null) {
            log.warn("无法获取用户会话: {}", openId);
            return null;
        }

        // 获取频道对应的 source 配置
        CrawlerConfig.ChannelConfig channel = configLoader.findChannelById(channelId);
        if (channel == null || channel.getSourceIds() == null || channel.getSourceIds().isEmpty()) {
            log.warn("未找到频道配置: {}", channelId);
            return null;
        }

        String sourceId = channel.getSourceIds().get(0);
        CrawlerConfig.SourceConfig source = configLoader.findSourceById(sourceId);
        if (source == null) {
            log.warn("未找到数据源配置: {}", sourceId);
            return null;
        }

        try {
            SmartSession smartSession = smartHttpClient.newSession(SmartCookieConverter.jsonToSmartCookies(session.getCookiesJson()));

            // 构建详情 URL
            String url = buildDetailUrl(source, id, categoryCode);
            log.debug("爬取详情: url={}", url);

            SmartResponse response = smartHttpClient.get(url, smartSession);
            if (!response.isSuccess()) {
                log.error("爬取详情失败: status={}", response.getStatusCode());
                return null;
            }

            // 获取解析器
            ParserStrategy parser = parserFactory.getContentParser(source.getParserType());
            ContentParserResult content = parser.parseContent(response.getBody(), source, id);

            if (content != null) {
                content.setId(id);
            }

            return content;

        } catch (Exception e) {
            log.error("爬取详情异常: channel={}, id={}, error={}", channelId, id, e.getMessage());
            return null;
        }
    }

    private String buildDetailUrl(CrawlerConfig.SourceConfig source, String id, String categoryCode) {
        String template = source.getDetailUrl();
        if (template == null) {
            return null;
        }

        String url = template
                .replace("{id}", id)
                .replace("{category}", categoryCode != null ? categoryCode : "");

        return url;
    }

    // ==================== 未读管理 ====================

    @Override
    public Map<String, Long> getUnreadCounts() {
        String openId = UserContext.getContext().getOpenId();
        Map<String, Long> counts = new LinkedHashMap<>();

        for (CrawlerConfig.ChannelConfig ch : configLoader.getChannels()) {
            counts.put(ch.getId(), infoCacheUtil.getUnreadCount(openId, ch.getId()));
        }

        return counts;
    }

    @Override
    public void markChannelRead(String channelId, String latestId) {
        String openId = UserContext.getContext().getOpenId();
        infoCacheUtil.setUserReadPosition(openId, channelId, latestId);
        log.debug("标记已读: openId={}, channel={}, latestId={}", openId, channelId, latestId);
    }
}