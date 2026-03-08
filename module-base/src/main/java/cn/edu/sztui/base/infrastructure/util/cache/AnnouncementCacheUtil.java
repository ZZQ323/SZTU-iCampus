package cn.edu.sztui.base.infrastructure.util.cache;

import cn.edu.sztui.base.application.vo.AnnouncementContentVo;
import cn.edu.sztui.base.application.vo.AnnouncementMetaVo;
import cn.edu.sztui.common.cache.redis.RedisKeyGenerator;
import cn.edu.sztui.common.cache.util.CacheUtil;
import cn.edu.sztui.common.cache.util.service.CacheService;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 公告缓存工具（修正版）
 * <p>
 * Redis 存储结构：
 * <ul>
 *   <li>announcements:meta         - Hash，公告元数据</li>
 *   <li>announcements:timeline     - ZSET，全局时间线（score=id）</li>
 *   <li>announcements:category:{code} - ZSET，分类索引</li>
 *   <li>announcements:latest_id    - String，最新公告ID</li>
 *   <li>announcements:content:{id} - String，详情缓存（TTL=24h）</li>
 *   <li>announcements:system       - Hash，系统状态</li>
 *   <li>announcements:hot-access   - ZSET，热点访问记录（score=访问时间戳）</li>
 * </ul>
 * <p>
 * 热点缓存说明：
 * - 最多缓存 50 篇详情（已解析为JSON结构）
 * - 按最后访问时间排序，淘汰最久未访问的
 */
@Slf4j
@Component
public class AnnouncementCacheUtil {

    private static final String META_KEY = "announcements:meta";
    private static final String TIMELINE_KEY = "announcements:timeline";
    private static final String CATEGORY_KEY_PREFIX = "announcements:category:";
    private static final String LATEST_ID_KEY = "announcements:latest_id";
    private static final String CONTENT_KEY_PREFIX = "announcements:content:";
    private static final String SYSTEM_KEY = "announcements:system";

    /**
     * 热点访问记录 ZSET
     */
    private static final String HOT_ACCESS_KEY = "announcements:hot-access";

    /**
     * 详情缓存 TTL：24小时（秒）
     */
    private static final long CONTENT_TTL_SECONDS = 24 * 60 * 60;

    /**
     * 最大缓存详情数量（改为50）
     */
    private static final int MAX_CACHED_DETAILS = 50;

    /**
     * 淘汰触发阈值
     */
    private static final int EVICT_THRESHOLD = MAX_CACHED_DETAILS + 10;

    @Resource
    private CacheUtil cacheUtil;

    @Resource
    private CacheService cacheService;

    @Resource
    private RedisKeyGenerator redisKeyGenerator;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    // ==================== 系统状态 ====================

    public boolean isSystemInitialized() {
        Object val = cacheUtil.hget(SYSTEM_KEY, "initialized");
        return "true".equals(String.valueOf(val));
    }

    public void setSystemInitialized(boolean initialized) {
        cacheUtil.hset(SYSTEM_KEY, "initialized", String.valueOf(initialized));
    }

    public String getActiveSourceOpenId() {
        Object val = cacheUtil.hget(SYSTEM_KEY, "activeSourceOpenId");
        return val != null ? val.toString() : null;
    }

    public void setActiveSourceOpenId(String openId) {
        cacheUtil.hset(SYSTEM_KEY, "activeSourceOpenId", openId != null ? openId : "");
    }

    public void updateLastCrawlTime() {
        cacheUtil.hset(SYSTEM_KEY, "lastCrawlTime", String.valueOf(System.currentTimeMillis()));
    }

    public Long getLastCrawlTime() {
        Object val = cacheUtil.hget(SYSTEM_KEY, "lastCrawlTime");
        return val != null ? Long.parseLong(val.toString()) : null;
    }

    public void clearActiveSource() {
        cacheUtil.hset(SYSTEM_KEY, "activeSourceOpenId", "");
        log.info("已清除 Cookie 来源");
    }

    // ==================== 公告元数据 ====================

    public void saveMeta(AnnouncementMetaVo meta) {
        cacheUtil.hset(META_KEY, meta.getId(), JSON.toJSONString(meta));

        String timelineKey = generateKey(TIMELINE_KEY);
        redisTemplate.opsForZSet().add(timelineKey, meta.getId(), Double.parseDouble(meta.getId()));

        if (StringUtils.hasText(meta.getCategory())) {
            String categoryKey = generateKey(CATEGORY_KEY_PREFIX + meta.getCategory());
            redisTemplate.opsForZSet().add(categoryKey, meta.getId(), Double.parseDouble(meta.getId()));
        }

        log.debug("保存公告元数据: id={}, title={}", meta.getId(), meta.getTitle());
    }

    public void saveMetaBatch(List<AnnouncementMetaVo> metas) {
        for (AnnouncementMetaVo meta : metas) {
            saveMeta(meta);
        }
        log.info("批量保存公告元数据: count={}", metas.size());
    }

    public AnnouncementMetaVo getMeta(String id) {
        Object val = cacheUtil.hget(META_KEY, id);
        if (val == null) return null;
        return JSON.parseObject(val.toString(), AnnouncementMetaVo.class);
    }

    public boolean hasMeta(String id) {
        return cacheUtil.hHasKey(META_KEY, id);
    }

    public String getLatestId() {
        String key = generateKey(LATEST_ID_KEY);
        Object val = cacheService.get(key);
        return val != null ? val.toString() : null;
    }

    public void setLatestId(String id) {
        String key = generateKey(LATEST_ID_KEY);
        cacheService.set(key, id);
    }

    // ==================== 列表查询 ====================

    public List<AnnouncementMetaVo> getList(int page, int pageSize) {
        long start = (long) (page - 1) * pageSize;
        long end = start + pageSize - 1;

        String key = generateKey(TIMELINE_KEY);
        Set<Object> ids = redisTemplate.opsForZSet().reverseRange(key, start, end);

        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        return ids.stream()
                .map(id -> getMeta(id.toString()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<AnnouncementMetaVo> getListByCategory(String category, int page, int pageSize) {
        long start = (long) (page - 1) * pageSize;
        long end = start + pageSize - 1;

        String key = generateKey(CATEGORY_KEY_PREFIX + category);
        Set<Object> ids = redisTemplate.opsForZSet().reverseRange(key, start, end);

        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        return ids.stream()
                .map(id -> getMeta(id.toString()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<AnnouncementMetaVo> getIncrementalList(String lastId) {
        if (!StringUtils.hasText(lastId)) {
            return Collections.emptyList();
        }

        double minScore = Double.parseDouble(lastId) + 1;

        String key = generateKey(TIMELINE_KEY);
        Set<Object> ids = redisTemplate.opsForZSet().rangeByScore(key, minScore, Double.MAX_VALUE);

        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        return ids.stream()
                .map(id -> getMeta(id.toString()))
                .filter(Objects::nonNull)
                .sorted((a, b) -> Long.compare(Long.parseLong(b.getId()), Long.parseLong(a.getId())))
                .collect(Collectors.toList());
    }

    public Long getTotalCount() {
        String key = generateKey(TIMELINE_KEY);
        return redisTemplate.opsForZSet().size(key);
    }

    public Long getTotalCountByCategory(String category) {
        String key = generateKey(CATEGORY_KEY_PREFIX + category);
        return redisTemplate.opsForZSet().size(key);
    }

    // ==================== 详情缓存（热点缓存） ====================

    /**
     * 保存详情内容（已解析为JSON结构）
     * TTL=24h
     */
    public void saveContent(AnnouncementContentVo content) {
        String key = generateKey(CONTENT_KEY_PREFIX + content.getId());
        cacheService.set(key, JSON.toJSONString(content), CONTENT_TTL_SECONDS);
        log.debug("保存公告详情缓存: id={}", content.getId());
    }

    /**
     * 获取详情内容（已解析的JSON结构）
     */
    public AnnouncementContentVo getContent(String id) {
        String key = generateKey(CONTENT_KEY_PREFIX + id);
        Object val = cacheService.get(key);
        if (val == null) return null;
        return JSON.parseObject(val.toString(), AnnouncementContentVo.class);
    }

    /**
     * 检查详情是否已缓存
     */
    public boolean hasContent(String id) {
        String key = generateKey(CONTENT_KEY_PREFIX + id);
        return Boolean.TRUE.equals(cacheService.hasKey(key));
    }

    // ==================== 热点访问管理 ====================

    /**
     * 记录热点访问
     * 每次访问详情时调用，用于 LRU 淘汰策略
     */
    public void recordAccess(String id) {
        String key = generateKey(HOT_ACCESS_KEY);
        redisTemplate.opsForZSet().add(key, id, System.currentTimeMillis());

        // 异步检查是否需要淘汰冷门缓存
        CompletableFuture.runAsync(this::evictColdContentIfNeeded);
    }

    /**
     * 检查并淘汰冷门缓存（如果需要）
     * 当缓存数量超过50时，删除最久未访问的
     */
    private void evictColdContentIfNeeded() {
        try {
            String hotKey = generateKey(HOT_ACCESS_KEY);
            Long count = redisTemplate.opsForZSet().size(hotKey);

            if (count == null || count <= EVICT_THRESHOLD) {
                return;
            }

            // 计算需要淘汰的数量
            int toEvict = count.intValue() - MAX_CACHED_DETAILS;

            // 获取最冷门的 N 条（score 最小的，即最久未访问的）
            Set<Object> coldIds = redisTemplate.opsForZSet().range(hotKey, 0, toEvict - 1);

            if (coldIds != null && !coldIds.isEmpty()) {
                for (Object id : coldIds) {
                    // 删除详情缓存
                    String contentKey = generateKey(CONTENT_KEY_PREFIX + id.toString());
                    cacheService.del(contentKey);

                    // 从热点记录中移除
                    redisTemplate.opsForZSet().remove(hotKey, id);
                }

                log.info("淘汰冷门详情缓存: {} 条，当前剩余: {}", coldIds.size(), MAX_CACHED_DETAILS);
            }

        } catch (Exception e) {
            log.warn("淘汰冷门缓存失败: {}", e.getMessage());
        }
    }

    /**
     * 批量预热详情缓存
     * 用于初始化时预爬取详情后，批量添加到热点记录
     */
    public void warmUpAccess(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        String key = generateKey(HOT_ACCESS_KEY);
        long now = System.currentTimeMillis();

        // 批量添加，使用递减的时间戳，让最新的文章排在前面
        for (int i = 0; i < ids.size(); i++) {
            redisTemplate.opsForZSet().add(key, ids.get(i), now - i);
        }

        log.info("预热详情缓存记录: {} 条", ids.size());
    }

    /**
     * 获取缓存统计信息
     */
    public Map<String, Object> getContentCacheStats() {
        String hotKey = generateKey(HOT_ACCESS_KEY);
        Long cachedCount = redisTemplate.opsForZSet().size(hotKey);

        Map<String, Object> stats = new HashMap<>();
        stats.put("cachedCount", cachedCount != null ? cachedCount : 0);
        stats.put("maxCount", MAX_CACHED_DETAILS);

        return stats;
    }

    // ==================== 标题搜索 ====================

    public List<AnnouncementMetaVo> searchByTitle(String keyword, int limit) {
        Map<Object, Object> allMetas = cacheUtil.hmget(META_KEY);
        if (allMetas == null || allMetas.isEmpty()) {
            return Collections.emptyList();
        }

        String lowerKeyword = keyword.toLowerCase();

        return allMetas.values().stream()
                .map(v -> JSON.parseObject(v.toString(), AnnouncementMetaVo.class))
                .filter(m -> m.getTitle() != null && m.getTitle().toLowerCase().contains(lowerKeyword))
                .sorted((a, b) -> Long.compare(Long.parseLong(b.getId()), Long.parseLong(a.getId())))
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ==================== 工具方法 ====================

    private String generateKey(String key) {
        return redisKeyGenerator.generate("cache:" + key);
    }
}