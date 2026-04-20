package cn.edu.sztui.stream.application.activity.service;

import cn.edu.sztui.common.cache.util.CacheUtil;
import cn.edu.sztui.stream.application.activity.client.DashScopeClient;
import cn.edu.sztui.stream.application.activity.vo.ActivityExtractionVo;
import cn.edu.sztui.stream.application.activity.vo.ScanResultVo;
import cn.edu.sztui.stream.application.service.InfoService;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ContentParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 活动抽取扫描服务（Step A 核心）
 * <p>
 * 只给管理员调试接口用，不挂任何事件监听。
 * 流程：取指定频道最近 N 篇 → 规则预筛 → 未命中跳过 → 命中送 LLM → Redis 缓存 → 返回结果行
 * <p>
 * 缓存 key：{@code icampus:cache:activity:extract:{articleId}:{cacheVersion}:{model}}
 * 升级 prompt / schema 只需在 yml 里 bump {@code ai.activity.cache-version} 即可自然失效。
 */
@Slf4j
@Service
public class ActivityScanService {

    /** 专用日志，方便用户在终端单独过滤看 */
    private static final org.slf4j.Logger scanLog = org.slf4j.LoggerFactory.getLogger("activity-scan");

    private static final String CACHE_PREFIX = "icampus:cache:activity:extract:";
    private static final long CACHE_TTL_SECONDS = 30L * 24 * 3600;  // 30 天

    @Resource
    private InfoService infoService;

    @Resource
    private InfoCacheUtil infoCacheUtil;

    @Resource
    private DashScopeClient dashScopeClient;

    @Resource
    private ActivityPreFilter preFilter;

    @Resource
    private CacheUtil cacheUtil;

    @Value("${ai.activity.content-max-chars:4000}")
    private int contentMaxChars;

    @Value("${ai.activity.cache-version:v1}")
    private String cacheVersion;

    @Value("${ai.activity.max-scan-count:50}")
    private int maxScanCount;

    // ==================== Prompt ====================

    private static final String SYSTEM_PROMPT = """
            你是校园信息助手，从给定的学校文章中判断是否是"校园活动"并抽取关键信息。

            定义：
              - 活动 = 师生可以参加的、有具体时间或地点的事件（讲座、比赛、招聘会、演出、研讨会、开放日等）
              - 非活动 = 政策通知、工作安排、规章制度、事后报道、表彰、人事任命、公示等

            严格按以下 JSON 结构返回，不要任何其他文字、不要代码块标记、不要解释：
            {
              "isActivity": true/false,
              "confidence": 0 到 1 之间的小数,
              "type": "讲座" | "沙龙" | "比赛" | "招聘会" | "演出" | "展览" | "会议" | "报告会" | "其他" | "",
              "title": "活动名（可与原文章标题不同）；非活动返回空字符串",
              "startAt": "YYYY-MM-DDTHH:mm 或 YYYY-MM-DD；未知返回空字符串",
              "endAt": "YYYY-MM-DDTHH:mm 或 YYYY-MM-DD；未知返回空字符串",
              "location": "地点；未知返回空字符串",
              "registration": "报名方式（扫码/邮件/链接等）；未知返回空字符串",
              "summary": "两句话内的活动摘要；非活动返回空字符串"
            }

            注意：
              - 时间要推断出具体日期。如果原文只说"下周三"，结合"发布日期"推算。
              - 如果文章标题/内容有明显的"政策规定"、"工作规程"字样，confidence 应低于 0.3。
              - 不要编造字段值，没有的信息返回空字符串即可。
            """;

    // ==================== 主流程 ====================

    public List<ScanResultVo> scan(List<String> channelIds, int limit, boolean force) {
        if (limit <= 0) limit = 10;
        if (limit > maxScanCount) limit = maxScanCount;

        List<ScanResultVo> out = new ArrayList<>();
        for (String channelId : channelIds) {
            List<ListParserResult.InfoItemMeta> items =
                    infoCacheUtil.getFeedList("", channelId, "", "", null, 1, limit);
            scanLog.info("channel={}: 取到 {} 条最近文章", channelId, items.size());
            for (ListParserResult.InfoItemMeta item : items) {
                out.add(processOne(item, force));
            }
        }
        return out;
    }

    private ScanResultVo processOne(ListParserResult.InfoItemMeta meta, boolean force) {
        ScanResultVo row = new ScanResultVo();
        row.setArticleId(meta.getId());
        row.setChannelId(meta.getChannelId());
        row.setSourceId(meta.getSourceId());
        row.setTitle(meta.getTitle());
        row.setUrl(meta.getUrl());
        row.setPublishDate(meta.getPublishDate());

        // 1. 拉取正文（优先缓存）
        String content = loadArticleText(meta);

        // 2. 规则预筛
        String reason = preFilter.judge(meta.getTitle(), content);
        row.setPassedPreFilter(reason != null);
        row.setPreFilterReason(reason);
        if (reason == null) {
            scanLog.info("skip(no-match): [{}] {} ", meta.getId(), meta.getTitle());
            return row;
        }

        // 3. 缓存
        String cacheKey = buildCacheKey(meta.getId());
        if (!force) {
            Object cached = cacheUtil.get(cacheKey);
            if (cached != null) {
                try {
                    ActivityExtractionVo vo = JSON.parseObject(cached.toString(), ActivityExtractionVo.class);
                    row.setAiResult(vo);
                    row.setFromCache(true);
                    row.setCalledAi(false);
                    scanLog.info("hit-cache: [{}] isActivity={} conf={} title={}",
                            meta.getId(), vo.isActivity(), vo.getConfidence(), vo.getTitle());
                    return row;
                } catch (JSONException e) {
                    log.warn("[activity-scan] cache parse fail, re-extracting: {}", e.getMessage());
                }
            }
        }

        // 4. 送 LLM
        String userContent = buildUserContent(meta, content);
        DashScopeClient.ChatResult chat = dashScopeClient.chatJson(SYSTEM_PROMPT, userContent);
        row.setCalledAi(true);
        row.setDurationMs(chat.getDurationMs());
        row.setPromptTokens(chat.getPromptTokens());
        row.setCompletionTokens(chat.getCompletionTokens());

        if (!chat.isOk()) {
            row.setError(chat.getError());
            scanLog.warn("ai-error: [{}] {} - {}", meta.getId(), meta.getTitle(), chat.getError());
            return row;
        }

        // 5. 解析 + 缓存
        try {
            ActivityExtractionVo vo = JSON.parseObject(chat.getContent(), ActivityExtractionVo.class);
            row.setAiResult(vo);
            cacheUtil.set(cacheKey, JSON.toJSONString(vo), CACHE_TTL_SECONDS);
            scanLog.info("ai-ok: [{}] isActivity={} conf={} title={} ({}ms, {} tokens)",
                    meta.getId(), vo.isActivity(), vo.getConfidence(),
                    vo.getTitle(), chat.getDurationMs(),
                    (chat.getPromptTokens() == null ? 0 : chat.getPromptTokens()) +
                            (chat.getCompletionTokens() == null ? 0 : chat.getCompletionTokens()));
        } catch (Exception e) {
            row.setError("parse JSON failed: " + e.getMessage() + " / raw: " + truncate(chat.getContent(), 200));
            scanLog.warn("ai-parse-fail: [{}] {} - {}", meta.getId(), meta.getTitle(), e.getMessage());
        }

        return row;
    }

    // ==================== 辅助 ====================

    /**
     * 拉正文：优先走详情缓存；未命中 fallback 到列表 meta 的 summary。
     * 详情缓存未命中时不触发远程爬取（避免调试时意外打学校），直接返回 summary/空。
     */
    private String loadArticleText(ListParserResult.InfoItemMeta meta) {
        if (meta.getChannelId() != null && meta.getId() != null) {
            ContentParserResult cached = infoCacheUtil.getContent(meta.getChannelId(), meta.getId());
            if (cached != null && cached.getContent() != null) {
                return htmlToPlain(cached.getContent());
            }
        }
        return meta.getSummary() == null ? "" : meta.getSummary();
    }

    private String htmlToPlain(String html) {
        if (html == null || html.isEmpty()) return "";
        return Jsoup.parse(html).text();
    }

    String buildUserContent(ListParserResult.InfoItemMeta meta, String content) {
        String truncated = truncate(content, contentMaxChars);
        return "标题：" + safe(meta.getTitle()) + "\n" +
                "来源：" + safe(meta.getSource()) + "\n" +
                "发布日期：" + safe(meta.getPublishDate()) + "\n" +
                "正文：\n" + truncated;
    }

    private String buildCacheKey(String articleId) {
        return CACHE_PREFIX + articleId + ":" + cacheVersion + ":" + dashScopeClient.getModel();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...[truncated]";
    }
}
