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
 * 公告缓存工具（增强版）
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
 * 新增功能：
 * <ul>
 *   <li>热点访问记录：记录每次详情访问，用于 LRU 淘汰</li>
 *   <li>自动淘汰冷门缓存：当缓存数量超过阈值时，自动删除最少访问的内容</li>
 *   <li>缓存统计：提供缓存命中率、总量等统计信息</li>
 * </ul>
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
     * 最大缓存详情数量
     */
    private static final int MAX_CACHED_DETAILS = 500;

    /**
     * 淘汰触发阈值（超过此数量时触发淘汰）
     */
    private static final int EVICT_THRESHOLD = MAX_CACHED_DETAILS + 50;

    @Resource
    private CacheUtil cacheUtil;

    @Resource
    private CacheService cacheService;

    @Resource
    private RedisKeyGenerator redisKeyGenerator;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    // ==================== 系统状态 ====================

    /**
     * 检查系统是否已初始化
     */
    public boolean isSystemInitialized() {
        Object val = cacheUtil.hget(SYSTEM_KEY, "initialized");
        return "true".equals(String.valueOf(val));
    }

    /**
     * 设置系统初始化状态
     */
    public void setSystemInitialized(boolean initialized) {
        cacheUtil.hset(SYSTEM_KEY, "initialized", String.valueOf(initialized));
    }

    /**
     * 获取当前活跃的Cookie来源OpenId
     */
    public String getActiveSourceOpenId() {
        Object val = cacheUtil.hget(SYSTEM_KEY, "activeSourceOpenId");
        return val != null ? val.toString() : null;
    }

    /**
     * 设置当前活跃的Cookie来源OpenId
     */
    public void setActiveSourceOpenId(String openId) {
        cacheUtil.hset(SYSTEM_KEY, "activeSourceOpenId", openId != null ? openId : "");
    }

    /**
     * 更新最后爬取时间
     */
    public void updateLastCrawlTime() {
        cacheUtil.hset(SYSTEM_KEY, "lastCrawlTime", String.valueOf(System.currentTimeMillis()));
    }

    /**
     * 获取最后爬取时间
     */
    public Long getLastCrawlTime() {
        Object val = cacheUtil.hget(SYSTEM_KEY, "lastCrawlTime");
        return val != null ? Long.parseLong(val.toString()) : null;
    }

    // ==================== 公告元数据 ====================

    /**
     * 保存公告元数据
     */
    public void saveMeta(AnnouncementMetaVo meta) {
        // 保存元数据到 Hash
        cacheUtil.hset(META_KEY, meta.getId(), JSON.toJSONString(meta));

        // 添加到全局时间线 ZSET（score = id 数值）
        String timelineKey = generateKey(TIMELINE_KEY);
        redisTemplate.opsForZSet().add(timelineKey, meta.getId(), Double.parseDouble(meta.getId()));

        // 添加到分类索引 ZSET
        if (StringUtils.hasText(meta.getCategory())) {
            String categoryKey = generateKey(CATEGORY_KEY_PREFIX + meta.getCategory());
            redisTemplate.opsForZSet().add(categoryKey, meta.getId(), Double.parseDouble(meta.getId()));
        }

        log.debug("保存公告元数据: id={}, title={}", meta.getId(), meta.getTitle());
    }

    /**
     * 批量保存公告元数据
     */
    public void saveMetaBatch(List<AnnouncementMetaVo> metas) {
        for (AnnouncementMetaVo meta : metas) {
            saveMeta(meta);
        }
        log.info("批量保存公告元数据: count={}", metas.size());
    }

    /**
     * 获取公告元数据
     */
    public AnnouncementMetaVo getMeta(String id) {
        Object val = cacheUtil.hget(META_KEY, id);
        if (val == null) return null;
        return JSON.parseObject(val.toString(), AnnouncementMetaVo.class);
    }

    /**
     * 检查公告是否存在
     */
    public boolean hasMeta(String id) {
        return cacheUtil.hHasKey(META_KEY, id);
    }

    /**
     * 获取最新公告ID
     */
    public String getLatestId() {
        String key = generateKey(LATEST_ID_KEY);
        Object val = cacheService.get(key);
        return val != null ? val.toString() : null;
    }

    /**
     * 设置最新公告ID
     */
    public void setLatestId(String id) {
        String key = generateKey(LATEST_ID_KEY);
        cacheService.set(key, id);
    }

    // ==================== 列表查询 ====================

    /**
     * 分页获取公告列表（按ID倒序）
     *
     * @param page     页码，从1开始
     * @param pageSize 每页数量
     * @return 公告元数据列表
     */
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

    /**
     * 按分类分页获取公告列表
     *
     * @param category 分类代码
     * @param page     页码，从1开始
     * @param pageSize 每页数量
     * @return 公告元数据列表
     */
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

    /**
     * 获取增量公告（id > lastId）
     *
     * @param lastId 上次已读的最新ID
     * @return 新公告列表（按ID倒序）
     */
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

    /**
     * 获取总数
     */
    public Long getTotalCount() {
        String key = generateKey(TIMELINE_KEY);
        return redisTemplate.opsForZSet().size(key);
    }

    /**
     * 获取分类总数
     */
    public Long getTotalCountByCategory(String category) {
        String key = generateKey(CATEGORY_KEY_PREFIX + category);
        return redisTemplate.opsForZSet().size(key);
    }

    // ==================== 详情缓存（增强版） ====================

    /**
     * 保存详情内容（TTL=24h）
     */
    public void saveContent(AnnouncementContentVo content) {
        String key = generateKey(CONTENT_KEY_PREFIX + content.getId());
        cacheService.set(key, JSON.toJSONString(content), CONTENT_TTL_SECONDS);
        log.debug("保存公告详情缓存: id={}", content.getId());
    }

    /**
     * 获取详情内容
     */
    public AnnouncementContentVo getContent(String id) {
        String key = generateKey(CONTENT_KEY_PREFIX + id);
        Object val = cacheService.get(key);
        if (val == null) return null;
        return JSON.parseObject(val.toString(), AnnouncementContentVo.class);
    }

    /**
     * 检查详情是否已缓存
     *
     * @param id 公告ID
     * @return 是否存在缓存
     */
    public boolean hasContent(String id) {
        String key = generateKey(CONTENT_KEY_PREFIX + id);
        return Boolean.TRUE.equals(cacheService.hasKey(key));
    }

    // ==================== 热点访问管理（新增） ====================

    /**
     * 记录热点访问
     * <p>
     * 每次访问详情时调用，用于 LRU 淘汰策略
     *
     * @param id 公告ID
     */
    public void recordAccess(String id) {
        String key = generateKey(HOT_ACCESS_KEY);
        redisTemplate.opsForZSet().add(key, id, System.currentTimeMillis());

        // 异步检查是否需要淘汰冷门缓存
        CompletableFuture.runAsync(this::evictColdContentIfNeeded);
    }

    /**
     * 检查并淘汰冷门缓存（如果需要）
     * <p>
     * 当缓存数量超过阈值时，删除最少访问的内容
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
     * 获取热点公告ID列表
     *
     * @param limit 数量限制
     * @return 按热度排序的公告ID列表（最热的在前）
     */
    public List<String> getHotIds(int limit) {
        String key = generateKey(HOT_ACCESS_KEY);
        Set<Object> ids = redisTemplate.opsForZSet().reverseRange(key, 0, limit - 1);

        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        return ids.stream()
                .map(Object::toString)
                .collect(Collectors.toList());
    }

    /**
     * 获取缓存统计信息
     *
     * @return 统计信息 Map
     */
    public Map<String, Object> getContentCacheStats() {
        String hotKey = generateKey(HOT_ACCESS_KEY);
        Long cachedCount = redisTemplate.opsForZSet().size(hotKey);

        Map<String, Object> stats = new HashMap<>();
        stats.put("cachedCount", cachedCount != null ? cachedCount : 0);
        stats.put("maxCount", MAX_CACHED_DETAILS);
        stats.put("evictThreshold", EVICT_THRESHOLD);

        return stats;
    }

    /**
     * 批量预热详情缓存
     * <p>
     * 用于初始化时预爬取详情后，批量添加到热点记录
     *
     * @param ids 公告ID列表
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

    // ==================== 标题搜索 ====================

    /**
     * 标题模糊搜索
     * <p>
     * 注意：这是内存扫描，性能有限，建议限制返回数量
     *
     * @param keyword 搜索关键词
     * @param limit   最大返回数量
     * @return 匹配的公告列表
     */
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

    /**
     * 生成完整的 Redis key（带前缀）
     */
    private String generateKey(String key) {
        return redisKeyGenerator.generate("cache:" + key);
    }

    /**
     * 清除活跃的Cookie来源
     */
    public void clearActiveSource() {
        cacheUtil.hset(SYSTEM_KEY, "activeSourceOpenId", "");
        log.info("已清除 Cookie 来源");
    }
}