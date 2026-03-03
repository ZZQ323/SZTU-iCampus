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
import cn.edu.sztui.common.util.browserpool.PlaywrightBrowserPool;
import cn.edu.sztui.common.util.enums.ResultCodeEnum;
import cn.edu.sztui.common.util.enums.SysReturnCode;
import cn.edu.sztui.common.util.exception.BusinessException;
import com.microsoft.playwright.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 公告服务实现
 */
@Slf4j
@Service
public class AnnouncementServiceImpl  implements AnnouncementService {

    /** 公告列表页 URL 模板 */
    private static final String LIST_URL_TEMPLATE =
            "https://nbw-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/list.jsp" +
                    "?urltype=tree.TreeTempUrl&wbtreeid=%s&a1020514p=%d&a1020514c=20";

    /** 公告详情页 URL 模板 */
    private static final String DETAIL_URL_TEMPLATE =
            "https://nbw-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/info/%s/%s.htm";

    @Resource
    private AnnouncementCacheUtil announcementCacheUtil;

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    @Resource
    private PlaywrightBrowserPool browserPool;

    @Resource
    private AnnouncementListParser listParser;

    @Resource
    private AnnouncementContentParser contentParser;

    // ==================== 查询接口 ====================

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

    @Override
    public AnnouncementListVo fullTextSearch(String openId, String keyword, Integer scope,
                                             String category, Integer page) {
        // TODO: 实现全文搜索，代理学校搜索接口
        // URL: POST https://nbw-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/ssjgkf.jsp?wbtreeid=1029
        // 参数: Find=find, INTEXT=关键词, sstj=scope, fwlb=category, a1020514p=page
        throw new UnsupportedOperationException("全文搜索待实现");
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
                .filter(m -> Long.parseLong(m.getId()) > currentLatestIdNum)
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
                .map(m -> Long.parseLong(m.getId()))
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

        return browserPool.executeWithContext(context -> {
            // 添加 Cookie
            context.addCookies(CookieConverter.fromCookieDTOs(session.getCookiesJson()));

            // 访问公告列表页（1029 = 全部）
            Page browserPage = context.newPage();
            String url = String.format(LIST_URL_TEMPLATE, "1029", page);

            log.debug("爬取公告列表: url={}", url);
            browserPage.navigate(url);
            browserPage.waitForLoadState();

            // 解析 HTML
            String html = browserPage.content();
            return listParser.parseList(html);

        }, browserPool.getSlowTimeoutSeconds());
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

        return browserPool.executeWithContext(context -> {
            context.addCookies(CookieConverter.fromCookieDTOs(session.getCookiesJson()));

            Page browserPage = context.newPage();
            String url = String.format(DETAIL_URL_TEMPLATE, category, id);

            log.debug("爬取公告详情: url={}", url);
            browserPage.navigate(url);
            browserPage.waitForLoadState();

            String html = browserPage.content();
            return contentParser.parse(html, id);

        }, browserPool.getDefaultTimeoutSeconds());
    }
}
