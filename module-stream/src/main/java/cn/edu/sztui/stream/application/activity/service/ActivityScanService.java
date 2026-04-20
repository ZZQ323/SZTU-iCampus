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

    @Resource
    private ActivityIndexService indexService;

    @Value("${ai.activity.content-max-chars:4000}")
    private int contentMaxChars;

    @Value("${ai.activity.cache-version:v1}")
    private String cacheVersion;

    @Value("${ai.activity.max-scan-count:50}")
    private int maxScanCount;

    // ==================== Prompt（V3：广义定义 + few-shot + 时间字段严格化） ====================
    //
    // 迭代历史：
    //   V0（初版）：zero-shot，狭义"事件"定义；F1≈0.61，pending 24%
    //   V1：活动定义扩宽到"可报名"；F1≈0.75（交换项目能抓到）
    //   V2：加 few-shot 示例；F1≈0.85（边界案例学习）
    //   V3（当前）：时间字段严格指令（结合发布日期推算、多日活动、报名截止）；pending < 15%

    private static final String SYSTEM_PROMPT = """
            你是深圳技术大学校园信息助手。从给定文章中判断是否是"校园活动"并抽取关键信息。

            【活动定义】isActivity=true 包括：
              a. 有固定时间地点的事件：讲座、比赛、演出、报告会、运动会、开放日、晚会、颁奖
              b. 可自由报名的项目或机会：交换项目、暑期项目、训练营、工作坊、招募、微专业招生
              c. 有明确参与方式的校园机会：读书会、沙龙、分享会、邀请函

            【非活动】isActivity=false 包括：
              - 行政流程：规章、规定、管理办法、公示、任命、表彰、通报
              - 工作会议：教学工作研讨会、博士后考核/开题/结题报告会、预算申报、谈话调研
              - 事后内容：xxx 总结、xxx 已圆满举行、xxx 座谈会情况
              - 信息公开：xxx 月度数据、xxx 年度报告

            【时间字段规则】
              - ISO 格式："YYYY-MM-DDTHH:mm"（含时间）或 "YYYY-MM-DD"（只有日期）
              - 原文有"5月28日"但未给年份 → 结合 publishDate 推算年份
              - 原文有"下周三"、"明天"等相对表述 → 结合 publishDate 推算具体日期
              - 原文说"具体时间另行通知"/"时间待定" → 返回空字符串 ""
              - 只给截止日期的报名类 → startAt="", endAt=截止日期
              - 多日活动 → startAt=开始日，endAt=结束日
              - 不要返回混合格式（如 "2026-05-25T下午"）
              - 不要瞎编时间。不确定就空字符串。

            【其他字段规则】
              - title：活动名称，去掉"关于"/"的通知"等前后缀
              - type：从 [讲座/沙龙/比赛/招聘会/演出/展览/会议/报告会/训练营/交换项目/招募/其他] 选一个；非活动为空
              - location：线上活动写"线上"；未知返回空
              - registration：报名方式简述（扫码/邮件/链接/截止日期）；未知返回空
              - summary：一到两句话概括；非活动为空

            【示例】

            示例 1（讲座，时间明确）：
            输入：标题="关于举办技大讲坛第一百二十一讲的通知"，发布日期=2026-04-15，正文包含"定于 4 月 28 日下午 2 点在学术报告厅"
            输出：{"isActivity":true,"confidence":0.95,"type":"讲座","title":"技大讲坛第一百二十一讲","startAt":"2026-04-28T14:00","endAt":"","location":"学术报告厅","registration":"","summary":"技大讲坛第121讲，学术报告厅举办。"}

            示例 2（交换项目，只有报名截止）：
            输入：标题="关于2026年秋季学期德国某大学交换项目报名的通知"，发布日期=2026-04-10，正文包含"申请材料于 5 月 30 日前提交"
            输出：{"isActivity":true,"confidence":0.88,"type":"交换项目","title":"德国某大学 2026 秋季交换项目","startAt":"","endAt":"2026-05-30","location":"","registration":"邮件提交材料，截止 5月30日","summary":"2026秋季学期德国交换项目，5月30日前报名。"}

            示例 3（比赛，多日）：
            输入：标题="关于举办第五届峥嵘杯辩论赛的通知"，发布日期=2026-04-18，正文包含"报名截止 5月5日，初赛于 5月10日至5月15日"
            输出：{"isActivity":true,"confidence":0.95,"type":"比赛","title":"第五届峥嵘杯辩论赛","startAt":"2026-05-10","endAt":"2026-05-15","location":"","registration":"报名截止 5月5日","summary":"第五届峥嵘杯辩论赛，5月10日至15日举行初赛。"}

            示例 4（非活动 - 公示）：
            输入：标题="关于人事任命的公示"
            输出：{"isActivity":false,"confidence":0.95,"type":"","title":"","startAt":"","endAt":"","location":"","registration":"","summary":""}

            示例 5（非活动 - 事后总结）：
            输入：标题="人工智能赋能教育教学教师工作坊（第二期）总结"
            输出：{"isActivity":false,"confidence":0.92,"type":"","title":"","startAt":"","endAt":"","location":"","registration":"","summary":""}

            【严格要求】只输出一个 JSON 对象，不要任何解释、markdown 代码块标记、额外字段。
            """;

    // ==================== 主流程 ====================

    public List<ScanResultVo> scan(List<String> channelIds, int limit, boolean force) {
        return scan(channelIds, limit, force, false);
    }

    /**
     * 扫描并抽取活动信息。
     *
     * @param channelIds       要扫描的频道 ID
     * @param limit            每频道取最近 N 篇
     * @param force            忽略缓存强制重跑
     * @param bypassPreFilter  为 true 时绕开规则预筛，所有文章都送 LLM（用于做"规则 vs 纯 LLM"对照实验）
     */
    public List<ScanResultVo> scan(List<String> channelIds, int limit, boolean force, boolean bypassPreFilter) {
        if (limit <= 0) limit = 10;
        if (limit > maxScanCount) {
            scanLog.warn("请求 limit={} 超过 ai.activity.max-scan-count={} 上限，已截断；如需更大调 yml",
                    limit, maxScanCount);
            limit = maxScanCount;
        }

        List<ScanResultVo> out = new ArrayList<>();
        for (String channelId : channelIds) {
            // 用 per-channel timeline（info:{channelId}:timeline），覆盖包含公文通在内的所有频道；
            // 全局 feed:timeline 不一定有公文通（Step A 测过就是 0 条）。
            List<ListParserResult.InfoItemMeta> items = infoCacheUtil.getList(channelId, 1, limit);
            scanLog.info("channel={} limit={} bypassPreFilter={}: 取到 {} 条最近文章",
                    channelId, limit, bypassPreFilter, items.size());
            for (ListParserResult.InfoItemMeta item : items) {
                out.add(processOne(item, force, bypassPreFilter));
            }
        }
        return out;
    }

    private ScanResultVo processOne(ListParserResult.InfoItemMeta meta, boolean force, boolean bypassPreFilter) {
        ScanResultVo row = new ScanResultVo();
        row.setArticleId(meta.getId());
        row.setChannelId(meta.getChannelId());
        row.setSourceId(meta.getSourceId());
        row.setTitle(meta.getTitle());
        row.setUrl(meta.getUrl());
        row.setPublishDate(meta.getPublishDate());

        // 1. 拉取正文（优先缓存）
        String content = loadArticleText(meta);

        // 2. 规则预筛（无论是否 bypass 都跑一次，结果保留用于对照实验）
        String reason = preFilter.judge(meta.getTitle(), content);
        row.setPassedPreFilter(reason != null);
        row.setPreFilterReason(reason);
        if (reason == null && !bypassPreFilter) {
            scanLog.info("skip(no-match): [{}] {} ", meta.getId(), meta.getTitle());
            return row;
        }
        // bypass 模式下 prefilter 失败的文章继续走 LLM，论文对照数据靠这里产生

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
                    // 缓存命中也顺便刷一次索引（幂等）——之前索引被清或未写过时能自愈
                    indexService.upsert(meta, vo);
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

        // 5. 解析 + 缓存 + 索引
        try {
            ActivityExtractionVo vo = JSON.parseObject(chat.getContent(), ActivityExtractionVo.class);
            row.setAiResult(vo);
            cacheUtil.set(cacheKey, JSON.toJSONString(vo), CACHE_TTL_SECONDS);
            indexService.upsert(meta, vo);
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
