package cn.edu.sztui.stream.infrastructure.util.cache;

import cn.edu.sztui.common.cache.redis.RedisKeyGenerator;
import cn.edu.sztui.common.cache.util.CacheUtil;
import cn.edu.sztui.common.cache.util.service.CacheService;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ContentParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 统一信息流缓存工具
 * <p>
 * 支持多频道（channel）的数据缓存，每个频道独立存储
 * <p>
 * Redis 存储结构（以 channelId 为前缀）：
 * <ul>
 *   <li>info:{channelId}:meta         - Hash，元数据</li>
 *   <li>info:{channelId}:timeline     - ZSET，时间线（score=id）</li>
 *   <li>info:{channelId}:category:{code} - ZSET，分类索引</li>
 *   <li>info:{channelId}:latest_id    - String，最新ID</li>
 *   <li>info:{channelId}:content:{id} - String，详情缓存（TTL=24h）</li>
 *   <li>info:{channelId}:system       - Hash，频道状态</li>
 *   <li>info:{channelId}:hot-access   - ZSET，热点访问记录</li>
 *   <li>info:user:{userId}:read:{channelId} - String，用户已读位置</li>
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

    private static final long CONTENT_TTL_SECONDS = 24 * 60 * 60;
    private static final int MAX_CACHED_DETAILS = 50;
    private static final int EVICT_THRESHOLD = MAX_CACHED_DETAILS + 10;

    @Resource
    private CacheUtil cacheUtil;

    @Resource
    private CacheService cacheService;

    @Resource
    private RedisKeyGenerator redisKeyGenerator;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    // ==================== 系统状态（按频道） ====================

    public boolean isChannelInitialized(String channelId) {
        String key = getSystemKey(channelId);
        Object val = cacheUtil.hget(key, "initialized");
        return "true".equals(String.valueOf(val));
    }

    public void setChannelInitialized(String channelId, boolean initialized) {
        String key = getSystemKey(channelId);
        cacheUtil.hset(key, "initialized", String.valueOf(initialized));
    }

    public String getActiveSourceUserId(String channelId) {
        String key = getSystemKey(channelId);
        Object val = cacheUtil.hget(key, "activeSourceUserId");
        return val != null && StringUtils.hasText(val.toString()) ? val.toString() : null;
    }

    public void setActiveSourceUserId(String channelId, String userId) {
        String key = getSystemKey(channelId);
        cacheUtil.hset(key, "activeSourceUserId", userId != null ? userId : "");
    }

    public boolean hasActiveSource(String channelId) {
        return StringUtils.hasText(getActiveSourceUserId(channelId));
    }

    public void clearActiveSource(String channelId) {
        String key = getSystemKey(channelId);
        cacheUtil.hset(key, "activeSourceUserId", "");
        log.info("已清除频道 {} 的 Cookie 来源", channelId);
    }

    // ==================== 元数据操作 ====================

    public void saveMeta(String channelId, ListParserResult.InfoItemMeta meta) {
        String metaJson = JSON.toJSONString(meta);

        // 1. 写入频道维度（保持原有逻辑）
        String metaKey = getMetaKey(channelId);
        cacheUtil.hset(metaKey, meta.getId(), metaJson);

        double score = idToScore(meta.getId());
        String timelineKey = generateKey(KEY_PREFIX + channelId + TIMELINE_SUFFIX);
        redisTemplate.opsForZSet().add(timelineKey, meta.getId(), score);

        if (StringUtils.hasText(meta.getCategoryCode())) {
            String categoryKey = generateKey(KEY_PREFIX + channelId + CATEGORY_PREFIX + meta.getCategoryCode());
            redisTemplate.opsForZSet().add(categoryKey, meta.getId(), score);
        }

        // 2. 写入全局 feed（用 channelId:id 作为唯一键，避免跨频道 id 冲突）
        String feedItemKey = channelId + ":" + meta.getId();
        String feedTimelineKey = generateKey(FEED_TIMELINE);
        String feedMetaKey = generateKey(FEED_META_PREFIX + feedItemKey);
        redisTemplate.opsForZSet().add(feedTimelineKey, feedItemKey, score);
        cacheService.set(feedMetaKey, metaJson);

        log.debug("保存元数据: channel={}, id={}, title={}", channelId, meta.getId(), meta.getTitle());
    }

    public void saveMetaBatch(String channelId, List<ListParserResult.InfoItemMeta> metas) {
        for (ListParserResult.InfoItemMeta meta : metas) {
            saveMeta(channelId, meta);
        }
        log.info("批量保存元数据: channel={}, count={}", channelId, metas.size());
    }

    public ListParserResult.InfoItemMeta getMeta(String channelId, String id) {
        String metaKey = getMetaKey(channelId);
        Object val = cacheUtil.hget(metaKey, id);
        if (val == null) return null;
        return JSON.parseObject(val.toString(), ListParserResult.InfoItemMeta.class);
    }

    public boolean hasMeta(String channelId, String id) {
        String metaKey = getMetaKey(channelId);
        return cacheUtil.hHasKey(metaKey, id);
    }

    public String getLatestId(String channelId) {
        String key = generateKey(KEY_PREFIX + channelId + LATEST_ID_SUFFIX);
        Object val = cacheService.get(key);
        return val != null ? val.toString() : null;
    }

    public void setLatestId(String channelId, String id) {
        String key = generateKey(KEY_PREFIX + channelId + LATEST_ID_SUFFIX);
        cacheService.set(key, id);
    }

    // ==================== 全局 Feed 查询 ====================

    /**
     * 从全局 feed 读取并过滤。
     * 先读取较多条目（overFetch），Java 内存过滤后截取分页。
     * 适合几千条量级的全局 timeline。
     */
    public List<ListParserResult.InfoItemMeta> getFeedList(
            String sourceOrg, String channelId, String contentType, String subContentType,
            int page, int pageSize) {

        String feedTimelineKey = generateKey(FEED_TIMELINE);

        // 读取全局 timeline 的全部 item key（最多 5000 条）
        Set<Object> allKeys = redisTemplate.opsForZSet().reverseRange(feedTimelineKey, 0, 4999);
        if (allKeys == null || allKeys.isEmpty()) {
            return Collections.emptyList();
        }

        // 读取每条 item 的元数据并过滤
        List<ListParserResult.InfoItemMeta> filtered = new ArrayList<>();
        for (Object keyObj : allKeys) {
            String feedItemKey = keyObj.toString();
            String feedMetaKey = generateKey(FEED_META_PREFIX + feedItemKey);
            Object val = cacheService.get(feedMetaKey);
            if (val == null) continue;

            ListParserResult.InfoItemMeta item = JSON.parseObject(val.toString(), ListParserResult.InfoItemMeta.class);
            if (item == null) continue;

            // 按条件过滤
            if (StringUtils.hasText(sourceOrg) && !sourceOrg.equals(item.getSourceOrg())) continue;
            if (StringUtils.hasText(channelId) && !channelId.equals(item.getChannelId())) continue;
            if (StringUtils.hasText(contentType) && !contentType.equals(item.getContentType())) continue;
            if (StringUtils.hasText(subContentType) && !subContentType.equals(item.getSubContentType())) continue;

            filtered.add(item);
        }

        // 分页
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
    public long getFeedCount(String sourceOrg, String channelId, String contentType, String subContentType) {
        // 简化实现：无过滤时直接返回 timeline 大小
        if (!StringUtils.hasText(sourceOrg) && !StringUtils.hasText(channelId)
                && !StringUtils.hasText(contentType) && !StringUtils.hasText(subContentType)) {
            String feedTimelineKey = generateKey(FEED_TIMELINE);
            Long size = redisTemplate.opsForZSet().zCard(feedTimelineKey);
            return size != null ? size : 0;
        }
        // 有过滤时需要全量扫描（量不大，可接受）
        return getFeedList(sourceOrg, channelId, contentType, subContentType, 1, 5000).size();
    }

    // ==================== 频道列表查询 ====================

    public List<ListParserResult.InfoItemMeta> getList(String channelId, int page, int pageSize) {
        long start = (long) (page - 1) * pageSize;
        long end = start + pageSize - 1;

        String key = generateKey(KEY_PREFIX + channelId + TIMELINE_SUFFIX);
        Set<Object> ids = redisTemplate.opsForZSet().reverseRange(key, start, end);

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

        String key = generateKey(KEY_PREFIX + channelId + CATEGORY_PREFIX + categoryCode);
        Set<Object> ids = redisTemplate.opsForZSet().reverseRange(key, start, end);

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
        String key = generateKey(KEY_PREFIX + channelId + TIMELINE_SUFFIX);
        Set<Object> ids = redisTemplate.opsForZSet().rangeByScore(key, minScore, Double.MAX_VALUE);

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
        String key = generateKey(KEY_PREFIX + channelId + TIMELINE_SUFFIX);
        return redisTemplate.opsForZSet().size(key);
    }

    public Long getTotalCountByCategory(String channelId, String categoryCode) {
        String key = generateKey(KEY_PREFIX + channelId + CATEGORY_PREFIX + categoryCode);
        return redisTemplate.opsForZSet().size(key);
    }

    // ==================== 详情缓存 ====================

    public void saveContent(String channelId, String id, ContentParserResult content) {
        String key = generateKey(KEY_PREFIX + channelId + CONTENT_PREFIX + id);
        cacheService.set(key, JSON.toJSONString(content), CONTENT_TTL_SECONDS);
        recordAccess(channelId, id);
        log.debug("保存详情缓存: channel={}, id={}", channelId, id);
    }

    public ContentParserResult getContent(String channelId, String id) {
        String key = generateKey(KEY_PREFIX + channelId + CONTENT_PREFIX + id);
        Object val = cacheService.get(key);
        if (val == null) return null;
        recordAccess(channelId, id);
        return JSON.parseObject(val.toString(), ContentParserResult.class);
    }

    public boolean hasContent(String channelId, String id) {
        String key = generateKey(KEY_PREFIX + channelId + CONTENT_PREFIX + id);
        return Boolean.TRUE.equals(cacheService.hasKey(key));
    }

    // ==================== 热点访问管理 ====================

    public void recordAccess(String channelId, String id) {
        String key = generateKey(KEY_PREFIX + channelId + HOT_ACCESS_SUFFIX);
        redisTemplate.opsForZSet().add(key, id, System.currentTimeMillis());
        CompletableFuture.runAsync(() -> evictColdContentIfNeeded(channelId));
    }

    private void evictColdContentIfNeeded(String channelId) {
        try {
            String hotKey = generateKey(KEY_PREFIX + channelId + HOT_ACCESS_SUFFIX);
            Long count = redisTemplate.opsForZSet().size(hotKey);

            if (count == null || count <= EVICT_THRESHOLD) {
                return;
            }

            int toEvict = count.intValue() - MAX_CACHED_DETAILS;
            Set<Object> coldIds = redisTemplate.opsForZSet().range(hotKey, 0, toEvict - 1);

            if (coldIds != null && !coldIds.isEmpty()) {
                for (Object id : coldIds) {
                    String contentKey = generateKey(KEY_PREFIX + channelId + CONTENT_PREFIX + id.toString());
                    cacheService.del(contentKey);
                    redisTemplate.opsForZSet().remove(hotKey, id);
                }
                log.info("淘汰冷门详情缓存: channel={}, count={}", channelId, coldIds.size());
            }
        } catch (Exception e) {
            log.warn("淘汰冷门缓存失败: {}", e.getMessage());
        }
    }

    // ==================== 用户已读管理 ====================

    public void setUserReadPosition(String userId, String channelId, String latestId) {
        String key = generateKey(USER_READ_PREFIX + userId + ":read:" + channelId);
        cacheService.set(key, latestId);
    }

    public String getUserReadPosition(String userId, String channelId) {
        String key = generateKey(USER_READ_PREFIX + userId + ":read:" + channelId);
        Object val = cacheService.get(key);
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
        String metaKey = getMetaKey(channelId);
        Map<Object, Object> allMetas = cacheUtil.hmget(metaKey);
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
        // 可以按需添加各频道的统计
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

    // ==================== 工具方法 ====================

    private String getMetaKey(String channelId) {
        return generateKey(KEY_PREFIX + channelId + META_SUFFIX);
    }

    private String getSystemKey(String channelId) {
        return generateKey(KEY_PREFIX + channelId + SYSTEM_SUFFIX);
    }

    private String generateKey(String key) {
        return redisKeyGenerator.generate("cache:" + key);
    }

    /**
     * 将 ID 转换为 ZSET score
     * <p>
     * 数字 ID：直接作为 score（保持原有排序）。
     * 非数字 ID（wx_xxx、ext_xxx）：用 hashCode 的绝对值，加负偏移避免与数字 ID 碰撞。
     * 非数字 ID 之间的相对顺序不重要（无法从 ID 推断时间），但不会与数字 ID 混淆。
     */
    private static double idToScore(String id) {
        try {
            return Double.parseDouble(id);
        } catch (NumberFormatException e) {
            // 非数字 ID：用负值区间，避免与正数 ID 碰撞
            return -Math.abs((double) id.hashCode());
        }
    }

    /**
     * 安全的 ID 降序比较器（数字 ID 排前面，非数字 ID 排后面）
     */
    private static final Comparator<ListParserResult.InfoItemMeta> INFO_COMPARATOR = (a, b) -> {
        Long aNum = parseIdSafe(a.getId());
        Long bNum = parseIdSafe(b.getId());
        if (aNum != null && bNum != null) return Long.compare(bNum, aNum);
        if (aNum != null) return -1; // 数字排前
        if (bNum != null) return 1;
        return b.getId().compareTo(a.getId()); // 都是非数字，字典序降序
    };

    private static Long parseIdSafe(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
    // ==================== 数据源级别状态（按 sourceId） ====================

    /**
     * 某数据源是否已完成初始化
     */
    public boolean isSourceInitialized(String sourceId) {
        String key = generateKey("info:source:" + sourceId + ":system");
        Object val = cacheUtil.hget(key, "initialized");
        return "true".equals(String.valueOf(val));
    }

    /**
     * 标记数据源已初始化
     */
    public void markSourceInitialized(String sourceId) {
        String key = generateKey("info:source:" + sourceId + ":system");
        cacheUtil.hset(key, "initialized", "true");
        log.info("数据源已标记初始化: {}", sourceId);
    }

    /**
     * 清除数据源的初始化标记（用于启动时强制重新初始化）
     */
    public void clearSourceInitialized(String sourceId) {
        String key = generateKey("info:source:" + sourceId + ":system");
        cacheUtil.hdel(key, "initialized");
    }

    /**
     * 更新数据源最后爬取时间（按 sourceId，非 channelId）
     */
    public void updateLastCrawlTime(String sourceId) {
        String key = generateKey("info:source:" + sourceId + ":system");
        cacheUtil.hset(key, "lastCrawlTime", String.valueOf(System.currentTimeMillis()));
    }

    /**
     * 获取数据源最后爬取时间
     */
    public Long getLastCrawlTime(String sourceId) {
        String key = generateKey("info:source:" + sourceId + ":system");
        Object val = cacheUtil.hget(key, "lastCrawlTime");
        return val != null ? Long.parseLong(val.toString()) : null;
    }

    // ==================== 全局 Cookie 来源（不分频道） ====================

    private static final String GLOBAL_SYSTEM_KEY = "info:global:system";

    /**
     * 获取全局活跃 Cookie 来源 userId
     * （CookieSourceManager 调用，不区分频道）
     */
    public String getActiveSourceUserId() {
        String key = generateKey(GLOBAL_SYSTEM_KEY);
        Object val = cacheUtil.hget(key, "activeSourceUserId");
        return val != null && StringUtils.hasText(val.toString()) ? val.toString() : null;
    }

    /**
     * 设置全局活跃 Cookie 来源 userId
     */
    public void setActiveSourceUserId(String userId) {
        String key = generateKey(GLOBAL_SYSTEM_KEY);
        cacheUtil.hset(key, "activeSourceUserId", userId != null ? userId : "");
    }

    /**
     * 清除全局活跃 Cookie 来源
     */
    public void clearActiveSource() {
        String key = generateKey(GLOBAL_SYSTEM_KEY);
        cacheUtil.hset(key, "activeSourceUserId", "");
        log.debug("已清除全局 Cookie 来源");
    }
}