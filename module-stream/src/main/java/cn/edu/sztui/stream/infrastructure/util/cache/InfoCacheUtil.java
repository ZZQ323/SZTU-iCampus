package cn.edu.sztui.stream.infrastructure.util.cache;

import cn.edu.sztui.common.cache.util.CacheUtil;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ContentParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 统一信息流缓存工具
 * <p>
 * 支持多频道（channel）的数据缓存，每个频道独立存储。
 * <p>
 * <b>所有 Redis 读写均通过 {@link CacheUtil}</b>（项目硬规则）。
 * 这里传入 cacheUtil 的 key 都是 <b>raw</b> 形式（不预加 {@code dev:sztu:cache:}），
 * cacheUtil 内部会做且仅做一次前缀包装。
 * <p>
 * Redis 实际落盘的 key 形如 {@code dev:sztu:cache:<rawKey>}，例如：
 * <ul>
 *   <li>{@code info:{channelId}:meta}     - Hash，文章元数据</li>
 *   <li>{@code info:{channelId}:timeline} - ZSET，时间线（score=id）</li>
 *   <li>{@code info:{channelId}:category:{code}} - ZSET，分类索引</li>
 *   <li>{@code info:{channelId}:latest_id}    - String，最新ID</li>
 *   <li>{@code info:{channelId}:content:{id}} - String，详情缓存（TTL=24h）</li>
 *   <li>{@code info:{channelId}:system}       - Hash，频道状态</li>
 *   <li>{@code info:{channelId}:hot-access}   - ZSET，热点访问记录</li>
 *   <li>{@code info:source:{sourceId}:system} - Hash，源级状态</li>
 *   <li>{@code info:global:system}            - Hash，全局活跃 cookie 源</li>
 *   <li>{@code info:user:{userId}:read:{channelId}} - String，用户已读位置</li>
 *   <li>{@code feed:timeline}                 - ZSET，全局聚合 timeline</li>
 *   <li>{@code feed:meta:{channelId}:{id}}    - String，全局 feed 元数据</li>
 * </ul>
 */
@Slf4j
@Component
public class InfoCacheUtil {

    private static final String KEY_PREFIX = "info:";
    private static final String META_SUFFIX = ":meta";
    private static final String TIMELINE_SUFFIX = ":timeline";
    private static final String CATEGORY_PREFIX = ":category:";
    private static final String LATEST_ID_SUFFIX = ":latest_id";
    private static final String CONTENT_PREFIX = ":content:";
    private static final String SYSTEM_SUFFIX = ":system";
    private static final String HOT_ACCESS_SUFFIX = ":hot-access";
    private static final String USER_READ_PREFIX = "info:user:";
    private static final String FEED_TIMELINE = "feed:timeline";
    private static final String FEED_META_PREFIX = "feed:meta:";
    private static final String GLOBAL_SYSTEM_KEY = "info:global:system";

    private static final long CONTENT_TTL_SECONDS = 24 * 60 * 60;
    private static final int MAX_CACHED_DETAILS = 50;
    private static final int EVICT_THRESHOLD = MAX_CACHED_DETAILS + 10;

    @Resource
    private CacheUtil cacheUtil;

    // ==================== 系统状态（按频道） ====================

    public boolean isChannelInitialized(String channelId) {
        Object val = cacheUtil.hget(getSystemKey(channelId), "initialized");
        return "true".equals(String.valueOf(val));
    }

    public void setChannelInitialized(String channelId, boolean initialized) {
        cacheUtil.hset(getSystemKey(channelId), "initialized", String.valueOf(initialized));
    }

    public String getActiveSourceUserId(String channelId) {
        Object val = cacheUtil.hget(getSystemKey(channelId), "activeSourceUserId");
        return val != null && StringUtils.hasText(val.toString()) ? val.toString() : null;
    }

    public void setActiveSourceUserId(String channelId, String userId) {
        cacheUtil.hset(getSystemKey(channelId), "activeSourceUserId", userId != null ? userId : "");
    }

    public boolean hasActiveSource(String channelId) {
        return StringUtils.hasText(getActiveSourceUserId(channelId));
    }

    public void clearActiveSource(String channelId) {
        cacheUtil.hset(getSystemKey(channelId), "activeSourceUserId", "");
        log.info("已清除频道 {} 的 Cookie 来源", channelId);
    }

    // ==================== 元数据操作 ====================

    public void saveMeta(String channelId, ListParserResult.InfoItemMeta meta) {
        String metaJson = JSON.toJSONString(meta);

        // 1. 频道维度 —— score=articleId（id 单调递增，latest_id / 增量查询都依赖此假设）
        cacheUtil.hset(getMetaKey(channelId), meta.getId(), metaJson);

        double idScore = idToScore(meta.getId());
        cacheUtil.zAdd(getTimelineKey(channelId), meta.getId(), idScore);

        if (StringUtils.hasText(meta.getCategoryCode())) {
            cacheUtil.zAdd(getCategoryKey(channelId, meta.getCategoryCode()), meta.getId(), idScore);
        }

        // 2. 全局 feed —— score=publishDate epoch（跨源真正可比）
        // 不同 source 的 id 序列各自独立，按 id 排会让 id 量级大的源霸占顶部。
        // 按 publishDate 才是用户语义上的"最新"。
        String feedItemKey = channelId + ":" + meta.getId();
        double feedScore = computeFeedScore(meta);
        cacheUtil.zAdd(FEED_TIMELINE, feedItemKey, feedScore);
        cacheUtil.set(FEED_META_PREFIX + feedItemKey, metaJson);

        log.debug("保存元数据: channel={}, id={}, title={}", channelId, meta.getId(), meta.getTitle());
    }

    /**
     * 重算单条 feed:timeline 的 score（迁移用，不动 meta）。
     * 给 admin 端点扫一遍历史数据时调用。
     */
    public void rebuildFeedScore(String channelId, ListParserResult.InfoItemMeta meta) {
        String feedItemKey = channelId + ":" + meta.getId();
        double feedScore = computeFeedScore(meta);
        cacheUtil.zAdd(FEED_TIMELINE, feedItemKey, feedScore);
    }

    public void saveMetaBatch(String channelId, List<ListParserResult.InfoItemMeta> metas) {
        for (ListParserResult.InfoItemMeta meta : metas) {
            saveMeta(channelId, meta);
        }
        log.info("批量保存元数据: channel={}, count={}", channelId, metas.size());
    }

    public ListParserResult.InfoItemMeta getMeta(String channelId, String id) {
        Object val = cacheUtil.hget(getMetaKey(channelId), id);
        if (val == null) return null;
        return JSON.parseObject(val.toString(), ListParserResult.InfoItemMeta.class);
    }

    public boolean hasMeta(String channelId, String id) {
        return cacheUtil.hHasKey(getMetaKey(channelId), id);
    }

    public String getLatestId(String channelId) {
        Object val = cacheUtil.get(getLatestIdKey(channelId));
        return val != null ? val.toString() : null;
    }

    public void setLatestId(String channelId, String id) {
        cacheUtil.set(getLatestIdKey(channelId), id);
    }

    // ==================== 全局 Feed 查询 ====================

    /**
     * 从全局 feed 读取并过滤。
     * 先读取较多条目（overFetch），Java 内存过滤后截取分页。
     * 适合几千条量级的全局 timeline。
     *
     * @param sourceIds 逗号分隔的 sourceId 白名单；非空时只保留这些 source 的 item
     */
    public List<ListParserResult.InfoItemMeta> getFeedList(
            String sourceOrg, String channelId, String contentType, String subContentType,
            String sourceIds, int page, int pageSize) {

        Set<String> sourceIdSet = parseCsvToSet(sourceIds);

        // 读取全局 timeline 的全部 item key（最多 5000 条）
        Set<Object> allKeys = cacheUtil.zReverseRange(FEED_TIMELINE, 0, 4999);
        if (allKeys == null || allKeys.isEmpty()) {
            return Collections.emptyList();
        }

        List<ListParserResult.InfoItemMeta> filtered = new ArrayList<>();
        for (Object keyObj : allKeys) {
            String feedItemKey = keyObj.toString();
            Object val = cacheUtil.get(FEED_META_PREFIX + feedItemKey);
            if (val == null) continue;

            ListParserResult.InfoItemMeta item = JSON.parseObject(val.toString(), ListParserResult.InfoItemMeta.class);
            if (item == null) continue;

            if (StringUtils.hasText(sourceOrg) && !sourceOrg.equals(item.getSourceOrg())) continue;
            if (StringUtils.hasText(channelId) && !channelId.equals(item.getChannelId())) continue;
            if (StringUtils.hasText(contentType) && !contentType.equals(item.getContentType())) continue;
            if (StringUtils.hasText(subContentType) && !subContentType.equals(item.getSubContentType())) continue;
            if (sourceIdSet != null && !sourceIdSet.contains(item.getSourceId())) continue;

            filtered.add(item);
        }

        int start = (page - 1) * pageSize;
        if (start >= filtered.size()) {
            return Collections.emptyList();
        }
        int end = Math.min(start + pageSize, filtered.size());
        return filtered.subList(start, end);
    }

    /**
     * 获取全局 feed 的总条目数（过滤后）
     */
    public long getFeedCount(String sourceOrg, String channelId, String contentType, String subContentType,
                             String sourceIds) {
        if (!StringUtils.hasText(sourceOrg) && !StringUtils.hasText(channelId)
                && !StringUtils.hasText(contentType) && !StringUtils.hasText(subContentType)
                && !StringUtils.hasText(sourceIds)) {
            Long size = cacheUtil.zCard(FEED_TIMELINE);
            return size != null ? size : 0;
        }
        return getFeedList(sourceOrg, channelId, contentType, subContentType, sourceIds, 1, 5000).size();
    }

    private static Set<String> parseCsvToSet(String csv) {
        if (!StringUtils.hasText(csv)) return null;
        Set<String> set = new HashSet<>();
        for (String s : csv.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) set.add(trimmed);
        }
        return set.isEmpty() ? null : set;
    }

    // ==================== 频道列表查询 ====================

    public List<ListParserResult.InfoItemMeta> getList(String channelId, int page, int pageSize) {
        long start = (long) (page - 1) * pageSize;
        long end = start + pageSize - 1;

        Set<Object> ids = cacheUtil.zReverseRange(getTimelineKey(channelId), start, end);

        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        return ids.stream()
                .map(id -> getMeta(channelId, id.toString()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<ListParserResult.InfoItemMeta> getListByCategory(String channelId, String categoryCode, int page, int pageSize) {
        long start = (long) (page - 1) * pageSize;
        long end = start + pageSize - 1;

        Set<Object> ids = cacheUtil.zReverseRange(getCategoryKey(channelId, categoryCode), start, end);

        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        return ids.stream()
                .map(id -> getMeta(channelId, id.toString()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<ListParserResult.InfoItemMeta> getIncrementalList(String channelId, String lastId) {
        if (!StringUtils.hasText(lastId)) {
            return Collections.emptyList();
        }

        double minScore = idToScore(lastId) + 1;
        Set<Object> ids = cacheUtil.zRangeByScore(getTimelineKey(channelId), minScore, Double.MAX_VALUE);

        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        return ids.stream()
                .map(id -> getMeta(channelId, id.toString()))
                .filter(Objects::nonNull)
                .sorted(INFO_COMPARATOR)
                .collect(Collectors.toList());
    }

    public Long getTotalCount(String channelId) {
        return cacheUtil.zCard(getTimelineKey(channelId));
    }

    public Long getTotalCountByCategory(String channelId, String categoryCode) {
        return cacheUtil.zCard(getCategoryKey(channelId, categoryCode));
    }

    // ==================== 详情缓存 ====================

    public void saveContent(String channelId, String id, ContentParserResult content) {
        cacheUtil.set(getContentKey(channelId, id), JSON.toJSONString(content), CONTENT_TTL_SECONDS);
        recordAccess(channelId, id);
        log.debug("保存详情缓存: channel={}, id={}", channelId, id);
    }

    public ContentParserResult getContent(String channelId, String id) {
        Object val = cacheUtil.get(getContentKey(channelId, id));
        if (val == null) return null;
        recordAccess(channelId, id);
        return JSON.parseObject(val.toString(), ContentParserResult.class);
    }

    public boolean hasContent(String channelId, String id) {
        return cacheUtil.hasKey(getContentKey(channelId, id));
    }

    // ==================== 热点访问管理 ====================

    /**
     * 记录访问（已禁用 LRU 淘汰）
     * <p>
     * <b>历史</b>：曾维护 hot-access ZSet 跟踪每频道最热 50 条详情，超阈值
     * 异步淘汰冷门 content key。但和 24h TTL 重复——TTL 已经兜底防无界增长，
     * LRU 让 backfill / activity scan 反复重拉详情，对学校不友好。
     * <p>
     * 现在保留方法签名（getContent / saveContent 还在调），但不做实际操作。
     * 详情 cache 完全靠 24h TTL 自动过期。
     */
    public void recordAccess(String channelId, String id) {
        // 已禁用：详情缓存依赖 24h TTL 兜底，无需 LRU 淘汰。
        // 保留方法是为了不改 saveContent / getContent 的调用方代码。
    }

    // ==================== 用户已读管理 ====================

    public void setUserReadPosition(String userId, String channelId, String latestId) {
        cacheUtil.set(getUserReadKey(userId, channelId), latestId);
    }

    public String getUserReadPosition(String userId, String channelId) {
        Object val = cacheUtil.get(getUserReadKey(userId, channelId));
        return val != null ? val.toString() : "0";
    }

    public long getUnreadCount(String userId, String channelId) {
        String readPosition = getUserReadPosition(userId, channelId);
        String latestId = getLatestId(channelId);

        if (latestId == null || readPosition == null) {
            return 0;
        }

        try {
            long readId = Long.parseLong(readPosition);
            long latest = Long.parseLong(latestId);
            return Math.max(0, latest - readId);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==================== 搜索 ====================

    public List<ListParserResult.InfoItemMeta> searchByTitle(String channelId, String keyword, int limit) {
        Map<Object, Object> allMetas = cacheUtil.hmget(getMetaKey(channelId));
        if (allMetas == null || allMetas.isEmpty()) {
            return Collections.emptyList();
        }

        String lowerKeyword = keyword.toLowerCase();

        return allMetas.values().stream()
                .map(v -> JSON.parseObject(v.toString(), ListParserResult.InfoItemMeta.class))
                .filter(m -> m.getTitle() != null && m.getTitle().toLowerCase().contains(lowerKeyword))
                .sorted(INFO_COMPARATOR)
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ==================== 统计信息 ====================

    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("maxCachedDetails", MAX_CACHED_DETAILS);
        return stats;
    }

    public Map<String, Object> getChannelStats(String channelId) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("channelId", channelId);
        stats.put("initialized", isChannelInitialized(channelId));
        stats.put("totalCount", getTotalCount(channelId));
        stats.put("latestId", getLatestId(channelId));
        stats.put("hasActiveSource", hasActiveSource(channelId));
        return stats;
    }

    // ==================== 数据源级别状态（按 sourceId） ====================

    /** 某数据源是否已完成初始化 */
    public boolean isSourceInitialized(String sourceId) {
        Object val = cacheUtil.hget(getSourceSystemKey(sourceId), "initialized");
        return "true".equals(String.valueOf(val));
    }

    /** 标记数据源已初始化 */
    public void markSourceInitialized(String sourceId) {
        cacheUtil.hset(getSourceSystemKey(sourceId), "initialized", "true");
        log.info("数据源已标记初始化: {}", sourceId);
    }

    /** 清除数据源的初始化标记（用于启动时强制重新初始化） */
    public void clearSourceInitialized(String sourceId) {
        cacheUtil.hdel(getSourceSystemKey(sourceId), "initialized");
    }

    /**
     * ⭐ 清除全局 feed timeline 和 meta 数据（启动时调用）
     * <p>
     * 当 channels/sources 配置变更（如拆分频道、改名）后，
     * 旧缓存中的 channelId/sourceOrgName 不会自动更新，
     * 启动时清除 feed 让重新爬取后填入正确数据。
     */
    public void clearFeedTimeline() {
        Set<Object> allKeys = cacheUtil.zRange(FEED_TIMELINE, 0, -1);
        if (allKeys != null) {
            for (Object keyObj : allKeys) {
                cacheUtil.del(FEED_META_PREFIX + keyObj.toString());
            }
        }
        cacheUtil.del(FEED_TIMELINE);
        log.info("已清除全局 feed timeline 和 {} 条 meta 数据", allKeys != null ? allKeys.size() : 0);
    }

    /**
     * 扫某频道下所有 meta，按新算法（publishDate 优先）重写 feed:timeline 的 score。
     * <p>
     * 不重爬学校、不动 meta、不动 info:{ch}:timeline，纯改 feed 全局聚合的排序权重。
     * 返回处理的条目数。
     */
    public int rebuildFeedTimelineForChannel(String channelId) {
        Map<Object, Object> allMetas = cacheUtil.hmget(getMetaKey(channelId));
        if (allMetas == null || allMetas.isEmpty()) return 0;
        int n = 0;
        for (Object v : allMetas.values()) {
            try {
                ListParserResult.InfoItemMeta meta = JSON.parseObject(v.toString(), ListParserResult.InfoItemMeta.class);
                if (meta == null || meta.getId() == null) continue;
                rebuildFeedScore(channelId, meta);
                n++;
            } catch (Exception e) {
                log.warn("[rebuild-feed] channel={} 解析 meta 失败: {}", channelId, e.getMessage());
            }
        }
        log.info("[rebuild-feed] channel={} 重算 {} 条 score", channelId, n);
        return n;
    }

    /** 更新数据源最后爬取时间（按 sourceId，非 channelId） */
    public void updateLastCrawlTime(String sourceId) {
        cacheUtil.hset(getSourceSystemKey(sourceId), "lastCrawlTime", String.valueOf(System.currentTimeMillis()));
    }

    /** 获取数据源最后爬取时间 */
    public Long getLastCrawlTime(String sourceId) {
        Object val = cacheUtil.hget(getSourceSystemKey(sourceId), "lastCrawlTime");
        return val != null ? Long.parseLong(val.toString()) : null;
    }

    // ==================== 全局 Cookie 来源（不分频道） ====================

    public String getActiveSourceUserId() {
        Object val = cacheUtil.hget(GLOBAL_SYSTEM_KEY, "activeSourceUserId");
        return val != null && StringUtils.hasText(val.toString()) ? val.toString() : null;
    }

    public void setActiveSourceUserId(String userId) {
        cacheUtil.hset(GLOBAL_SYSTEM_KEY, "activeSourceUserId", userId != null ? userId : "");
    }

    public void clearActiveSource() {
        cacheUtil.hset(GLOBAL_SYSTEM_KEY, "activeSourceUserId", "");
        log.debug("已清除全局 Cookie 来源");
    }

    // ==================== Raw Key 构造（不预加 dev:sztu:cache: 前缀，由 cacheUtil 自加） ====================

    private String getMetaKey(String channelId) {
        return KEY_PREFIX + channelId + META_SUFFIX;
    }

    private String getSystemKey(String channelId) {
        return KEY_PREFIX + channelId + SYSTEM_SUFFIX;
    }

    private String getTimelineKey(String channelId) {
        return KEY_PREFIX + channelId + TIMELINE_SUFFIX;
    }

    private String getCategoryKey(String channelId, String categoryCode) {
        return KEY_PREFIX + channelId + CATEGORY_PREFIX + categoryCode;
    }

    private String getLatestIdKey(String channelId) {
        return KEY_PREFIX + channelId + LATEST_ID_SUFFIX;
    }

    private String getContentKey(String channelId, String id) {
        return KEY_PREFIX + channelId + CONTENT_PREFIX + id;
    }

    private String getHotAccessKey(String channelId) {
        return KEY_PREFIX + channelId + HOT_ACCESS_SUFFIX;
    }

    private String getSourceSystemKey(String sourceId) {
        return KEY_PREFIX + "source:" + sourceId + SYSTEM_SUFFIX;
    }

    private String getUserReadKey(String userId, String channelId) {
        return USER_READ_PREFIX + userId + ":read:" + channelId;
    }

    /**
     * 将 ID 转换为 ZSET score（仅供频道级 timeline / category 使用）
     * <p>
     * 数字 ID：直接作为 score（保持原有排序）。
     * 非数字 ID（wx_xxx、ext_xxx）：用 hashCode 的绝对值，加负偏移避免与数字 ID 碰撞。
     */
    private static double idToScore(String id) {
        try {
            return Double.parseDouble(id);
        } catch (NumberFormatException e) {
            return -Math.abs((double) id.hashCode());
        }
    }

    /**
     * 计算 feed:timeline 的 score。
     * <p>
     * <b>三层 tier 设计</b>，下层永远不会盖过上层：
     * <pre>
     * Tier 1 (publishDate 已知)：TIER_PUBDATE + publishDateMs + crawledAt 日内偏移
     * Tier 2 (仅有 crawledAt)：  TIER_CRAWLED + crawledAt
     * Tier 3 (兜底)：            idToScore（可能为负）
     * </pre>
     * 为何分层：直接把 crawledAt 当 fallback 会让"无 publishDate 但今天刚爬到"
     * 的条目（score ≈ 1.71e12）盖过"4 月 30 日发布"的条目（score ≈ 1.71e12 也是，
     * 但 crawledAt 那条数值更大）。tier 偏移确保 publishDate 已知的总是排在前面，
     * 符合用户语义"按发布时间最新"。
     * <p>
     * Double 精度：53-bit mantissa = 9.007e15，足以容纳 2e15 + epochMillis（1.7e12）
     * 而不丢精度。
     */
    static double computeFeedScore(ListParserResult.InfoItemMeta meta) {
        Long pdEpochSec = parsePublishDateEpochSec(meta.getPublishDate());
        if (pdEpochSec != null) {
            long base = pdEpochSec * 1000L;
            long offset;
            if (meta.getCrawledAt() != null) {
                offset = Math.floorMod(meta.getCrawledAt(), 86_400_000L);
            } else {
                offset = (long) (Math.abs(meta.getId().hashCode()) % 86_400_000);
            }
            return TIER_PUBDATE + (double) (base + offset);
        }
        if (meta.getCrawledAt() != null) {
            return TIER_CRAWLED + meta.getCrawledAt().doubleValue();
        }
        return idToScore(meta.getId());
    }

    /** publishDate 已知层基线（远高于 epochMillis 量级，下层永远到不了） */
    static final double TIER_PUBDATE = 2_000_000_000_000_000.0;
    /** 仅 crawledAt 已知层基线 */
    static final double TIER_CRAWLED = 1_000_000_000_000_000.0;

    /**
     * 解析 publishDate 字符串为 epoch second。
     * <p>
     * 支持格式：<code>2026-04-30 / 2026-4-30 / 2026.04.30 / 2026/04/30 / 2026年4月30日</code>，
     * 末尾可带时间（忽略）。无法解析返回 null。
     */
    static Long parsePublishDateEpochSec(String s) {
        if (!StringUtils.hasText(s)) return null;
        String normalized = s.trim()
                .replace("年", "-")
                .replace("月", "-")
                .replace("日", "")
                .replace("/", "-")
                .replace(".", "-");
        // 截取首段（去掉可能跟着的时间 "14:30" 等）
        Matcher m = DATE_HEAD.matcher(normalized);
        if (!m.find()) return null;
        try {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            int d = Integer.parseInt(m.group(3));
            if (y < 1970 || y > 2999 || mo < 1 || mo > 12 || d < 1 || d > 31) return null;
            return LocalDate.of(y, mo, d).atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return null;
        }
    }

    private static final Pattern DATE_HEAD = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");

    /** 安全的 ID 降序比较器（数字 ID 排前面，非数字 ID 排后面） */
    private static final Comparator<ListParserResult.InfoItemMeta> INFO_COMPARATOR = (a, b) -> {
        Long aNum = parseIdSafe(a.getId());
        Long bNum = parseIdSafe(b.getId());
        if (aNum != null && bNum != null) return Long.compare(bNum, aNum);
        if (aNum != null) return -1;
        if (bNum != null) return 1;
        return b.getId().compareTo(a.getId());
    };

    private static Long parseIdSafe(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
