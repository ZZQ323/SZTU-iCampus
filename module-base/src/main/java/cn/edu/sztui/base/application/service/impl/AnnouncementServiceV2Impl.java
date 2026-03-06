package cn.edu.sztui.base.application.service.impl;

import cn.edu.sztui.base.application.service.AnnouncementService;
import cn.edu.sztui.base.application.vo.AnnouncementContentVo;
import cn.edu.sztui.base.application.vo.AnnouncementListVo;
import cn.edu.sztui.base.application.vo.AnnouncementMetaVo;
import cn.edu.sztui.base.infrastructure.convertor.CookieConverter;
import cn.edu.sztui.base.infrastructure.util.cache.AnnouncementCacheUtil;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.base.infrastructure.util.praser.AnnouncementContentParser;
import cn.edu.sztui.base.infrastructure.util.praser.AnnouncementListParser;
import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.common.util.enums.ResultCodeEnum;
import cn.edu.sztui.common.util.enums.SysReturnCode;
import cn.edu.sztui.common.util.exception.BusinessException;
import cn.edu.sztui.common.util.smarthttp.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 公告服务 V2 实现（基于 SmartHttpClient，无浏览器）
 * 
 * 【特性】：
 * - 使用纯 HTTP 请求，无需 Playwright
 * - 支持更高并发
 * - 内存占用极低
 * - 实现全文搜索
 */
@Slf4j
@Service("announcementServiceV2")
public class AnnouncementServiceV2Impl implements AnnouncementService {

    /** 公告列表页 URL 模板 */
    private static final String LIST_URL_TEMPLATE =
            "https://nbw-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/list.jsp" +
                    "?urltype=tree.TreeTempUrl&wbtreeid=%s&a1020514p=%d&a1020514c=20";

    /** 公告详情页 URL 模板 */
    private static final String DETAIL_URL_TEMPLATE =
            "https://nbw-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/info/%s/%s.htm";

    /** 全文搜索 URL */
    private static final String SEARCH_URL =
            "https://nbw-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/ssjgkf.jsp?wbtreeid=1029";

    @Resource
    private AnnouncementCacheUtil announcementCacheUtil;

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    @Resource
    private SmartHttpClient smartHttpClient;

    @Resource
    private AnnouncementListParser listParser;

    @Resource
    private AnnouncementContentParser contentParser;

    // ==================== 查询接口（从缓存）====================

    @Override
    public AnnouncementListVo getList(String category, Integer page, Integer pageSize) {
        page = page != null ? page : 1;
        pageSize = pageSize != null ? pageSize : 20;

        List<AnnouncementMetaVo> list;
        Long total;

        if (StringUtils.hasText(category)) {
            list = announcementCacheUtil.getListByCategory(category, page, pageSize);
            total = announcementCacheUtil.getTotalCountByCategory(category);
        } else {
            list = announcementCacheUtil.getList(page, pageSize);
            total = announcementCacheUtil.getTotalCount();
        }

        AnnouncementListVo vo = new AnnouncementListVo();
        vo.setList(list);
        vo.setLatestId(announcementCacheUtil.getLatestId());
        vo.setTotal(total != null ? total : 0L);
        vo.setHasMore(list.size() == pageSize);

        return vo;
    }

    @Override
    public AnnouncementContentVo getDetail(String openId, String id) {
        // 1. 尝试从缓存获取
        AnnouncementContentVo cached = announcementCacheUtil.getContent(id);
        if (cached != null) {
            log.debug("命中详情缓存: id={}", id);
            return cached;
        }

        // 2. 获取元数据以确定分类
        AnnouncementMetaVo meta = announcementCacheUtil.getMeta(id);
        String category = meta != null ? meta.getCategory() : "1018";

        // 3. 爬取详情页
        AnnouncementContentVo content = crawlDetail(openId, category, id);

        // 4. 保存缓存
        if (content != null && content.getContent() != null) {
            announcementCacheUtil.saveContent(content);
        }

        return content;
    }

    @Override
    public List<AnnouncementMetaVo> getIncremental(String lastId) {
        return announcementCacheUtil.getIncrementalList(lastId);
    }

    @Override
    public List<AnnouncementMetaVo> searchByTitle(String keyword, int limit) {
        return announcementCacheUtil.searchByTitle(keyword, limit);
    }

    // ==================== 全文搜索（⭐新实现）====================

    @Override
    public AnnouncementListVo fullTextSearch(String openId, String keyword, Integer scope,
                                             String category, Integer page) {
        ProxySession session = authSessionCacheUtil.getSession(openId);
        if (session == null) {
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "无法获取会话，请先登录",
                    ResultCodeEnum.UNAUTHORIZED.getCode()
            );
        }

        try (SmartSession smartSession = createSmartSession(session)) {
            // 构建搜索表单
            Map<String, String> formData = buildSearchFormData(keyword, scope, category, page);

            log.debug("全文搜索: keyword={}, scope={}, category={}, page={}", 
                    keyword, scope, category, page);

            // 发送 POST 请求
            SmartResponse response = smartHttpClient.post(SEARCH_URL, formData, smartSession);

            if (!response.isSuccess()) {
                log.error("全文搜索请求失败: status={}", response.getStatusCode());
                throw new BusinessException(
                        SysReturnCode.BASE_PROXY.getCode(),
                        "搜索请求失败",
                        ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
                );
            }

            // 解析搜索结果（复用列表解析器）
            String html = response.getBody();
            List<AnnouncementMetaVo> list = listParser.parseList(html);
            int totalPage = listParser.parseTotalPage(html);

            AnnouncementListVo vo = new AnnouncementListVo();
            vo.setList(list);
            vo.setTotal((long) totalPage * 20);  // 估算
            vo.setHasMore(page != null && page < totalPage);

            log.info("全文搜索完成: keyword={}, 找到 {} 条", keyword, list.size());
            return vo;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("全文搜索失败: {}", e.getMessage(), e);
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "搜索失败: " + e.getMessage(),
                    ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
            );
        }
    }

    /**
     * 构建搜索表单数据
     * 
     * 参数说明：
     * - INTEXT: 搜索关键词
     * - sstj: 1=全部, 2=标题, 3=正文
     * - fwlb: 1018=教务, 1019=科研, 1020=行政, 1021=学工, 1022=校园
     * - a1020514p: 页码
     */
    private Map<String, String> buildSearchFormData(String keyword, Integer scope, 
                                                     String category, Integer page) {
        Map<String, String> form = new LinkedHashMap<>();
        
        // 必填参数
        form.put("Find", "find");
        form.put("INTEXT", keyword != null ? keyword : "");
        form.put("sstj", scope != null ? String.valueOf(scope) : "1");  // 1=全部
        form.put("a1020514p", page != null ? String.valueOf(page) : "1");
        
        // 分类筛选
        if (StringUtils.hasText(category)) {
            form.put("fwlb", category);
        } else {
            form.put("fwlb", "");  // 空=全部分类
        }
        
        // 硬编码参数
        form.put("a1020514c", "20");
        form.put("a1020514t", "111");
        form.put("condition", "0");
        form.put("entrymode", "1");
        form.put("x", "0");
        form.put("y", "0");
        form.put("INTEXT2", "");
        form.put("news_search_code", "");
        
        return form;
    }

    // ==================== 爬取逻辑 ====================

    @Override
    public List<AnnouncementMetaVo> crawlIncremental(String sourceOpenId) {
        // 获取当前最新ID
        String currentLatestId = announcementCacheUtil.getLatestId();
        long currentLatestIdNum = currentLatestId != null ? Long.parseLong(currentLatestId) : 0;

        // 爬取第一页
        List<AnnouncementMetaVo> firstPage = crawlAnnouncementList(sourceOpenId, 1);

        if (firstPage.isEmpty()) {
            log.warn("爬取结果为空");
            return Collections.emptyList();
        }

        // 筛选新公告（id > currentLatestId）
        List<AnnouncementMetaVo> newAnnouncements = firstPage.stream()
                .filter(m -> {
                    try {
                        return Long.parseLong(m.getId()) > currentLatestIdNum;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                })
                .collect(Collectors.toList());

        if (newAnnouncements.isEmpty()) {
            log.debug("无新公告");
            announcementCacheUtil.updateLastCrawlTime();
            return Collections.emptyList();
        }

        // 保存新公告
        announcementCacheUtil.saveMetaBatch(newAnnouncements);

        // 更新最新ID
        String newLatestId = newAnnouncements.stream()
                .map(m -> {
                    try {
                        return Long.parseLong(m.getId());
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                })
                .max(Long::compareTo)
                .map(String::valueOf)
                .orElse(currentLatestId);
        
        announcementCacheUtil.setLatestId(newLatestId);
        announcementCacheUtil.updateLastCrawlTime();

        log.info("发现 {} 条新公告，新 latestId: {}", newAnnouncements.size(), newLatestId);

        return newAnnouncements;
    }

    @Override
    public List<AnnouncementMetaVo> crawlAnnouncementList(String openId, int page) {
        ProxySession session = authSessionCacheUtil.getSession(openId);
        if (session == null) {
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "无法获取会话",
                    ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
            );
        }

        try (SmartSession smartSession = createSmartSession(session)) {
            // 1029 = 全部分类
            String url = String.format(LIST_URL_TEMPLATE, "1029", page);
            
            log.debug("爬取公告列表: url={}", url);
            
            SmartResponse response = smartHttpClient.get(url, smartSession);

            if (!response.isSuccess()) {
                log.error("爬取列表失败: status={}", response.getStatusCode());
                return Collections.emptyList();
            }

            return listParser.parseList(response.getBody());

        } catch (Exception e) {
            log.error("爬取列表异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 爬取公告详情
     */
    private AnnouncementContentVo crawlDetail(String openId, String category, String id) {
        ProxySession session = authSessionCacheUtil.getSession(openId);
        if (session == null) {
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "无法获取会话",
                    ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
            );
        }

        try (SmartSession smartSession = createSmartSession(session)) {
            String url = String.format(DETAIL_URL_TEMPLATE, category, id);
            
            log.debug("爬取公告详情: url={}", url);
            
            SmartResponse response = smartHttpClient.get(url, smartSession);

            if (!response.isSuccess()) {
                log.error("爬取详情失败: status={}, id={}", response.getStatusCode(), id);
                return null;
            }

            return contentParser.parse(response.getBody(), id);

        } catch (Exception e) {
            log.error("爬取详情异常: id={}, error={}", id, e.getMessage());
            return null;
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 从 ProxySession 创建 SmartSession
     */
    private SmartSession createSmartSession(ProxySession proxySession) {
        if (proxySession == null || proxySession.getCookiesJson() == null 
                || proxySession.getCookiesJson().isEmpty()) {
            return smartHttpClient.newSession();
        }

        List<SmartCookie> cookies = CookieConverter.jsonToSmartCookies(proxySession.getCookiesJson());
        log.debug("从缓存加载了 {} 个 Cookie", cookies.size());
        return smartHttpClient.newSession(cookies);
    }
}
