package cn.edu.sztui.stream.application.service.impl;

import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.common.util.smarthttp.SmartCookie;
import cn.edu.sztui.common.util.smarthttp.SmartHttpClient;
import cn.edu.sztui.common.util.smarthttp.SmartResponse;
import cn.edu.sztui.common.util.smarthttp.SmartSession;
import cn.edu.sztui.stream.application.service.AnnouncementService;
import cn.edu.sztui.stream.infrastructure.persistence.entity.textDTO.AnnouncementContentVo;
import cn.edu.sztui.stream.infrastructure.persistence.entity.textDTO.AnnouncementListVo;
import cn.edu.sztui.stream.infrastructure.persistence.entity.textDTO.AnnouncementMetaVo;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.SourceConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfigLoader;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ContentParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ParserFactory;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ParserStrategy;
import cn.edu.sztui.stream.infrastructure.util.cache.AnnouncementCacheUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 公告服务实现
 * 
 * 文件位置：module-stream/src/main/java/cn/edu/sztui/stream/application/service/impl/AnnouncementServiceImpl.java
 */
@Slf4j
@Service
public class AnnouncementServiceImpl implements AnnouncementService {

    /** 公文通频道 ID */
    private static final String CHANNEL_ID = "announcement";

    @Resource
    private CrawlerConfigLoader configLoader;

    @Resource
    private ParserFactory parserFactory;

    @Resource
    private AnnouncementCacheUtil announcementCacheUtil;

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    @Resource
    private SmartHttpClient smartHttpClient;

    // ==================== 列表查询 ====================

    @Override
    public AnnouncementListVo getList(String category, int page, int pageSize) {
        AnnouncementListVo vo = new AnnouncementListVo();

        List<AnnouncementMetaVo> list;
        Long total;

        if (StringUtils.hasText(category)) {
            list = announcementCacheUtil.getListByCategory(category, page, pageSize);
            total = announcementCacheUtil.getTotalCountByCategory(category);
        } else {
            list = announcementCacheUtil.getList(page, pageSize);
            total = announcementCacheUtil.getTotalCount();
        }

        vo.setList(list);
        vo.setTotal(total);
        vo.setLatestId(announcementCacheUtil.getLatestId());
        vo.setHasMore(list.size() >= pageSize);

        return vo;
    }

    @Override
    public List<AnnouncementMetaVo> getIncremental(String lastId) {
        return announcementCacheUtil.getIncrementalList(lastId);
    }

    // ==================== 详情查询 ====================

    @Override
    public AnnouncementContentVo getDetail(String wxOpenId, String id) {
        // 1. 先查缓存
        AnnouncementContentVo cached = announcementCacheUtil.getContent(id);
        if (cached != null) {
            log.debug("详情命中缓存: id={}", id);
            announcementCacheUtil.recordAccess(id);
            return cached;
        }

        // 2. 缓存未命中，需要爬取
        log.info("详情缓存未命中，开始爬取: id={}", id);

        // 获取元数据以确定分类
        AnnouncementMetaVo meta = announcementCacheUtil.getMeta(id);
        String categoryCode = meta != null ? meta.getCategoryCode() : null;

        // 获取数据源配置
        SourceConfig sourceConfig = getSourceConfig(categoryCode);
        if (sourceConfig == null) {
            log.error("找不到数据源配置: categoryCode={}", categoryCode);
            return null;
        }

        // 构建详情 URL
        String detailUrl = sourceConfig.buildDetailUrl(id);
        if (detailUrl == null) {
            log.error("无法构建详情 URL: id={}", id);
            return null;
        }

        // 获取用户 Session
        SmartSession session = getUserSession(wxOpenId);
        if (session == null) {
            log.error("无法获取用户 Session: wxOpenId={}", wxOpenId);
            return null;
        }

        try {
            // 爬取详情页
            SmartResponse response = smartHttpClient.get(detailUrl, session);

            if (response.getStatusCode() != 200) {
                log.error("爬取详情失败: id={}, status={}", id, response.getStatusCode());
                return null;
            }

            // 解析详情
            ParserStrategy parser = parserFactory.getParser(sourceConfig.getParser());
            if (parser == null) {
                log.error("找不到解析器: type={}", sourceConfig.getParser());
                return null;
            }

            ContentParserResult result = parser.parseContent(response.getBody(), id, sourceConfig);
            AnnouncementContentVo content = result.toVo();

            // 保存到缓存
            announcementCacheUtil.saveContent(content);
            announcementCacheUtil.recordAccess(id);

            return content;

        } catch (Exception e) {
            log.error("爬取详情异常: id={}", id, e);
            return null;
        }
    }

    // ==================== 搜索 ====================

    @Override
    public List<AnnouncementMetaVo> searchByTitle(String keyword, int limit) {
        return announcementCacheUtil.searchByTitle(keyword, limit);
    }

    @Override
    public AnnouncementListVo fullTextSearch(String wxOpenId, String keyword, Integer scope, String category, Integer page) {
        // TODO: 代理学校的全文搜索接口
        // 这里暂时用本地标题搜索替代
        log.warn("全文搜索暂未实现，使用本地标题搜索替代");
        
        List<AnnouncementMetaVo> results = searchByTitle(keyword, 50);
        
        AnnouncementListVo vo = new AnnouncementListVo();
        vo.setList(results);
        vo.setTotal((long) results.size());
        vo.setHasMore(false);
        
        return vo;
    }

    // ==================== 爬取相关 ====================

    @Override
    public List<AnnouncementMetaVo> crawlIncremental(String wxOpenId) {
        // 获取默认数据源
        SourceConfig sourceConfig = configLoader.getDefaultSource(CHANNEL_ID);
        if (sourceConfig == null) {
            log.error("找不到默认数据源");
            return Collections.emptyList();
        }

        // 获取用户 Session
        SmartSession session = getUserSession(wxOpenId);
        if (session == null) {
            log.error("无法获取用户 Session: wxOpenId={}", wxOpenId);
            return Collections.emptyList();
        }

        try {
            // 爬取第一页
            String listUrl = sourceConfig.buildListUrl(1);
            SmartResponse response = smartHttpClient.get(listUrl, session);

            if (response.getStatusCode() != 200) {
                log.error("爬取列表失败: status={}", response.getStatusCode());
                return Collections.emptyList();
            }

            // 解析列表
            ParserStrategy parser = parserFactory.getParser(sourceConfig.getParser());
            if (parser == null) {
                log.error("找不到解析器: type={}", sourceConfig.getParser());
                return Collections.emptyList();
            }

            ListParserResult result = parser.parseList(response.getBody(), sourceConfig);

            // 过滤出新公告
            String currentLatestId = announcementCacheUtil.getLatestId();
            List<AnnouncementMetaVo> newItems = filterNewItems(result.getItems(), currentLatestId);

            if (!newItems.isEmpty()) {
                // 保存新公告
                announcementCacheUtil.saveMetaBatch(newItems);

                // 更新最新 ID
                String newLatestId = newItems.get(0).getId();
                announcementCacheUtil.setLatestId(newLatestId);

                log.info("增量爬取完成: {} 条新公告", newItems.size());
            }

            // 更新爬取时间
            announcementCacheUtil.updateLastCrawlTime();

            return newItems;

        } catch (Exception e) {
            log.error("增量爬取异常", e);
            return Collections.emptyList();
        }
    }

    @Override
    public int initialize(String wxOpenId) {
        log.info("开始初始化公告系统");
        
        int totalPage = getTotalPage(wxOpenId);
        if (totalPage <= 0) {
            log.error("无法获取总页数");
            return 0;
        }

        List<AnnouncementMetaVo> allItems = new ArrayList<>();

        for (int page = 1; page <= totalPage; page++) {
            try {
                List<AnnouncementMetaVo> pageItems = crawlPage(wxOpenId, page);
                allItems.addAll(pageItems);
                
                // 避免请求过快
                Thread.sleep(200);
                
            } catch (Exception e) {
                log.warn("第 {} 页爬取失败: {}", page, e.getMessage());
            }
        }

        if (!allItems.isEmpty()) {
            announcementCacheUtil.saveMetaBatch(allItems);
            announcementCacheUtil.setLatestId(allItems.get(0).getId());
        }

        log.info("初始化完成: {} 条公告", allItems.size());
        return allItems.size();
    }

    @Override
    public int getTotalPage(String wxOpenId) {
        SourceConfig sourceConfig = configLoader.getDefaultSource(CHANNEL_ID);
        if (sourceConfig == null) {
            return -1;
        }

        SmartSession session = getUserSession(wxOpenId);
        if (session == null) {
            return -1;
        }

        try {
            String listUrl = sourceConfig.buildListUrl(1);
            SmartResponse response = smartHttpClient.get(listUrl, session);

            if (response.getStatusCode() != 200) {
                return -1;
            }

            ParserStrategy parser = parserFactory.getParser(sourceConfig.getParser());
            if (parser == null) {
                return -1;
            }

            int totalPage = parser.parseTotalPage(response.getBody());
            return Math.min(totalPage, sourceConfig.getMaxPages());

        } catch (Exception e) {
            log.error("获取总页数失败", e);
            return -1;
        }
    }

    @Override
    public List<AnnouncementMetaVo> crawlPage(String wxOpenId, int page) {
        SourceConfig sourceConfig = configLoader.getDefaultSource(CHANNEL_ID);
        if (sourceConfig == null) {
            return Collections.emptyList();
        }

        SmartSession session = getUserSession(wxOpenId);
        if (session == null) {
            return Collections.emptyList();
        }

        try {
            String listUrl = sourceConfig.buildListUrl(page);
            SmartResponse response = smartHttpClient.get(listUrl, session);

            if (response.getStatusCode() != 200) {
                return Collections.emptyList();
            }

            ParserStrategy parser = parserFactory.getParser(sourceConfig.getParser());
            if (parser == null) {
                return Collections.emptyList();
            }

            ListParserResult result = parser.parseList(response.getBody(), sourceConfig);
            return result.getItems();

        } catch (Exception e) {
            log.error("爬取第 {} 页失败", page, e);
            return Collections.emptyList();
        }
    }

    @Override
    public void preCrawlDetails(String wxOpenId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        log.info("开始预爬取详情: {} 条", ids.size());

        List<String> successIds = new ArrayList<>();

        for (String id : ids) {
            try {
                AnnouncementContentVo content = getDetail(wxOpenId, id);
                if (content != null) {
                    successIds.add(id);
                }
                
                // 避免请求过快
                Thread.sleep(100);
                
            } catch (Exception e) {
                log.warn("预爬取详情失败: id={}", id);
            }
        }

        // 预热访问记录
        announcementCacheUtil.warmUpAccess(successIds);
        
        log.info("预爬取详情完成: 成功 {} 条", successIds.size());
    }

    // ==================== 私有方法 ====================

    /**
     * 获取数据源配置
     */
    private SourceConfig getSourceConfig(String categoryCode) {
        if (StringUtils.hasText(categoryCode)) {
            SourceConfig source = configLoader.findSourceByCategoryCode(CHANNEL_ID, categoryCode);
            if (source != null) {
                return source;
            }
        }
        return configLoader.getDefaultSource(CHANNEL_ID);
    }

    /**
     * 获取用户 Session
     */
    private SmartSession getUserSession(String wxOpenId) {
        if (!StringUtils.hasText(wxOpenId)) {
            return null;
        }

        ProxySession proxySession = authSessionCacheUtil.getSession(wxOpenId);
        if (proxySession == null || proxySession.getCookies() == null) {
            return null;
        }

        // 转换为 SmartCookie 列表
        List<SmartCookie> cookies = proxySession.getCookies().stream()
                .map(c -> SmartCookie.builder()
                        .name(c.getName())
                        .value(c.getValue())
                        .domain(c.getDomain())
                        .path(c.getPath())
                        .build())
                .collect(Collectors.toList());

        return smartHttpClient.newSession(cookies);
    }

    /**
     * 过滤出新公告
     */
    private List<AnnouncementMetaVo> filterNewItems(List<AnnouncementMetaVo> items, String currentLatestId) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }

        if (!StringUtils.hasText(currentLatestId)) {
            return items;
        }

        long latestIdNum;
        try {
            latestIdNum = Long.parseLong(currentLatestId);
        } catch (NumberFormatException e) {
            return items;
        }

        return items.stream()
                .filter(item -> {
                    try {
                        return Long.parseLong(item.getId()) > latestIdNum;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                })
                .collect(Collectors.toList());
    }
}
