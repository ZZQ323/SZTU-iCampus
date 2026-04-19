package cn.edu.sztui.stream.application.service.impl;

import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.bean.TokenMessage;
import cn.edu.sztui.common.util.smarthttp.SmartCookieConverter;
import cn.edu.sztui.common.util.smarthttp.dto.SmartResponse;
import cn.edu.sztui.common.util.smarthttp.service.SmartHttpClient;
import cn.edu.sztui.common.util.smarthttp.service.SmartSession;
import cn.edu.sztui.stream.application.external.engine.ArticleUrlResolver;
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
            info.setDescription(ch.getDescription());
            info.setIcon(ch.getIcon());
            info.setSourceOrg(ch.getSourceOrg() != null ? ch.getSourceOrg() : "fixed");
            info.setSort(ch.getSort());
            info.setUnreadCount(infoCacheUtil.getUnreadCount(userId, ch.getId()));

            List<CategoryInfo> categories = new ArrayList<>();
            List<String> contentTypes = new ArrayList<>();
            List<SourceInfo> sourceInfos = new ArrayList<>();
            if (ch.getSourceIds() != null) {
                for (String sourceId : ch.getSourceIds()) {
                    CrawlerConfig.SourceConfig source = configLoader.findSourceById(sourceId);
                    if (source != null) {
                        // 收集 contentType（去重）
                        if (source.getContentType() != null && !contentTypes.contains(source.getContentType())) {
                            contentTypes.add(source.getContentType());
                        }
                        if (source.getCategories() != null) {
                            source.getCategories().forEach((code, name) -> {
                                categories.add(new CategoryInfo(code, name));
                            });
                        }
                        // 收集 source 信息（前端订阅管理用）
                        sourceInfos.add(new SourceInfo(
                                source.getId(), source.getName(),
                                source.getContentType(), source.getSubContentType()
                        ));
                    }
                }
            }
            info.setContentTypes(contentTypes);
            info.setCategories(categories);
            info.setSources(sourceInfos);
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
    public InfoListResult getFeed(String sourceOrg, String channelId, String contentType, String subContentType, int page, int pageSize) {
        List<ListParserResult.InfoItemMeta> list = infoCacheUtil.getFeedList(
                sourceOrg, channelId, contentType, subContentType, page, pageSize);
        long total = infoCacheUtil.getFeedCount(sourceOrg, channelId, contentType, subContentType);

        InfoListResult result = new InfoListResult();
        result.setItems(list);
        result.setTotal(total);
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

        // 2. 获取元数据以确定分类、数据源和原始 URL
        String sourceId = null;
        String originalUrl = null;
        String metaTitle = null;
        ListParserResult.InfoItemMeta meta = infoCacheUtil.getMeta(channelId, id);
        if (meta != null) {
            if (!StringUtils.hasText(categoryCode)) {
                categoryCode = meta.getCategoryCode();
                log.debug("从缓存元数据获取到分类: id={}, category={}", id, categoryCode);
            }
            sourceId = meta.getSourceId();
            originalUrl = meta.getUrl(); // ⭐ 列表解析时提取的原始 URL（可能指向不同域名）
            metaTitle = meta.getTitle();
        }

        // 2.5 外链短路：条目指向非 sztu 域名（微信/政府网站等）时，直接返回外链而不爬取
        if (StringUtils.hasText(originalUrl)
                && originalUrl.startsWith("http")
                && ArticleUrlResolver.isExternalLink(originalUrl)) {
            log.debug("外链条目，跳过爬取: id={}, url={}", id, originalUrl);
            ContentParserResult external = ContentParserResult.builder()
                    .success(true)
                    .id(id)
                    .title(metaTitle)
                    .externalUrl(originalUrl)
                    .build();
            infoCacheUtil.saveContent(channelId, id, external);
            return external;
        }

        // 3. 爬取详情页
        ContentParserResult content = crawlDetail(channelId, id, categoryCode, sourceId, originalUrl);

        // 4. 保存到缓存
        if (content != null && content.isSuccess()) {
            infoCacheUtil.saveContent(channelId, id, content);
        }

        return content;
    }

    /**
     * 爬取详情页
     * <p>
     * ⭐ URL 确定策略：
     * 1. 优先使用元数据中的原始 URL（列表解析时已提取，可能指向不同域名如 nbw.sztu.edu.cn）
     * 2. fallback 使用 source 的 detailUrlTemplate 构建
     * <p>
     * 为什么需要原始 URL？
     * 部分部门网站（如 hr.sztu.edu.cn）列表页中的文章链接实际指向 WebVPN 网关域名
     * （nbw.sztu.edu.cn），用 detailUrlTemplate 构建的 URL 会 404。
     */
    private ContentParserResult crawlDetail(String channelId, String id, String categoryCode,
                                             String sourceId, String originalUrl) {
        TokenMessage ctx = UserContext.getContext();
        String cookiesJson = (ctx != null) ? ctx.getSchoolCookiesJson() : null;
        if (cookiesJson == null || cookiesJson.isEmpty()) {
            log.warn("无法获取用户 Cookie");
            return ContentParserResult.fail("无法获取用户会话");
        }

        // 查找 source config（用于解析器类型和 baseUrl）
        CrawlerConfig.SourceConfig source = findSourceForDetail(channelId, categoryCode, sourceId);
        if (source == null) {
            log.warn("未找到匹配的数据源: channel={}, category={}", channelId, categoryCode);
            return ContentParserResult.fail("未找到匹配的数据源");
        }

        try {
            SmartSession smartSession = smartHttpClient.newSession(
                    SmartCookieConverter.jsonToSmartCookies(cookiesJson));

            // ⭐ 优先使用元数据中的原始 URL，fallback 用 template 构建
            String url = null;
            if (StringUtils.hasText(originalUrl) && originalUrl.startsWith("http")) {
                url = originalUrl;
                log.debug("使用元数据原始 URL: {}", url);
            }
            if (url == null) {
                url = buildDetailUrl(source, id, categoryCode);
            }
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
     * ⭐ 三级查找策略确定正确的 source
     * <p>
     * 1. sourceId 精确匹配（从元数据获取，最可靠 —— 就是爬取该文章时使用的 source）
     * 2. categoryCode 匹配（按分类代码在频道内查找）
     * 3. fallback 到频道第一个 source
     * <p>
     * 为什么需要 sourceId？
     * department 频道聚合了 16+ 不同域名的 source，同一域名下的文章可能有不同 categoryCode
     * （如 hr.sztu.edu.cn 的 1020 和 1043）。仅靠 categoryCode 无法区分域名。
     * 但 sourceId（如 "hr-tzgg"）直接指向正确的 detailUrlTemplate。
     */
    private CrawlerConfig.SourceConfig findSourceForDetail(String channelId, String categoryCode, String sourceId) {
        // 1. sourceId 精确匹配（最可靠）
        if (StringUtils.hasText(sourceId)) {
            CrawlerConfig.SourceConfig source = configLoader.findSourceById(sourceId);
            if (source != null) {
                log.debug("按 sourceId 匹配到数据源: sourceId={}", sourceId);
                return source;
            }
        }

        CrawlerConfig.ChannelConfig channel = configLoader.findChannelById(channelId);
        if (channel == null || channel.getSourceIds() == null || channel.getSourceIds().isEmpty()) {
            return null;
        }

        // 2. categoryCode 精确匹配
        if (StringUtils.hasText(categoryCode)) {
            for (String sid : channel.getSourceIds()) {
                CrawlerConfig.SourceConfig source = configLoader.findSourceById(sid);
                if (source != null && categoryCode.equals(source.getCategoryCode())) {
                    log.debug("按分类匹配到数据源: category={} → source={}", categoryCode, source.getId());
                    return source;
                }
            }
            log.warn("未找到匹配分类 {} 的数据源，使用默认", categoryCode);
        }

        // 3. Fallback：使用第一个 source
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