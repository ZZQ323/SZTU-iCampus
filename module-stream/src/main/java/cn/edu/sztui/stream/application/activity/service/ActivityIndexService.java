package cn.edu.sztui.stream.application.activity.service;

import cn.edu.sztui.common.cache.util.CacheUtil;
import cn.edu.sztui.stream.application.activity.vo.ActivityExtractionVo;
import cn.edu.sztui.stream.application.activity.vo.ActivityIndexItem;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 活动索引。将 LLM 判定为活动、且 startAt 可解析的条目写入 Redis ZSET（score=epochMillis），
 * 时间无法解析的落入 pending Set，供前端"时间待定"区域展示。
 * <p>
 * 这个服务职责单一：写 + 查。判定 / 规则筛选 / LLM 调用都在 {@code ActivityScanService} 里。
 * <p>
 * Redis keys:
 * <ul>
 *   <li>{@code icampus:cache:activity:timeline} — ZSET score=epochMillis member=articleId</li>
 *   <li>{@code icampus:cache:activity:pending}  — SET  member=articleId（时间待定）</li>
 *   <li>{@code icampus:cache:activity:detail:{id}} — STRING JSON 活动详情</li>
 * </ul>
 */
@Slf4j
@Service
public class ActivityIndexService {

    static final String KEY_TIMELINE = "icampus:cache:activity:timeline";
    static final String KEY_PENDING = "icampus:cache:activity:pending";
    static final String KEY_DETAIL_PREFIX = "icampus:cache:activity:detail:";

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private CacheUtil cacheUtil;

    // ==================== 写入 ====================

    /**
     * 用 LLM 结果刷新索引。
     *   - ai.isActivity=false / ai=null → 从索引删除（可能是之前判过又改变主意）
     *   - isActivity=true + 时间可解析 → 写 timeline ZSET
     *   - isActivity=true + 时间无法解析 → 写 pending Set
     */
    public void upsert(ListParserResult.InfoItemMeta meta, ActivityExtractionVo ai) {
        if (ai == null || !ai.isActivity()) {
            remove(meta.getId());
            return;
        }

        ActivityIndexItem item = new ActivityIndexItem();
        item.setArticleId(meta.getId());
        item.setChannelId(meta.getChannelId());
        item.setSourceId(meta.getSourceId());
        item.setSourceOrgName(meta.getSourceOrgName());
        item.setArticleUrl(meta.getUrl());
        item.setType(ai.getType());
        item.setTitle(StringUtils.hasText(ai.getTitle()) ? ai.getTitle() : meta.getTitle());
        item.setStartAt(ai.getStartAt());
        item.setEndAt(ai.getEndAt());
        item.setLocation(ai.getLocation());
        item.setRegistration(ai.getRegistration());
        item.setSummary(ai.getSummary());
        item.setConfidence(ai.getConfidence());

        Long epoch = ActivityTimeParser.parseToEpochMillis(ai.getStartAt());
        item.setStartAtEpoch(epoch);

        cacheUtil.set(KEY_DETAIL_PREFIX + meta.getId(), JSON.toJSONString(item));

        if (epoch != null) {
            redisTemplate.opsForZSet().add(KEY_TIMELINE, meta.getId(), epoch);
            redisTemplate.opsForSet().remove(KEY_PENDING, meta.getId());
            log.debug("[ActivityIndex] upsert timeline: id={} epoch={} title={}",
                    meta.getId(), epoch, item.getTitle());
        } else {
            redisTemplate.opsForSet().add(KEY_PENDING, meta.getId());
            redisTemplate.opsForZSet().remove(KEY_TIMELINE, meta.getId());
            log.debug("[ActivityIndex] upsert pending (unparseable time): id={} startAt={}",
                    meta.getId(), ai.getStartAt());
        }
    }

    public void remove(String articleId) {
        redisTemplate.opsForZSet().remove(KEY_TIMELINE, articleId);
        redisTemplate.opsForSet().remove(KEY_PENDING, articleId);
        cacheUtil.del(KEY_DETAIL_PREFIX + articleId);
    }

    /** 调试用：返回当前索引大小 */
    public IndexStats stats() {
        Long tlSize = redisTemplate.opsForZSet().zCard(KEY_TIMELINE);
        Long pdSize = redisTemplate.opsForSet().size(KEY_PENDING);
        return new IndexStats(tlSize == null ? 0 : tlSize, pdSize == null ? 0 : pdSize);
    }

    // ==================== 查询 ====================

    /**
     * 时间范围查询。range 闭区间 [fromMs, toMs]，按时间升序返回前 max 条。
     */
    public List<ActivityIndexItem> queryByRange(long fromMs, long toMs, int maxResults) {
        Set<Object> ids = redisTemplate.opsForZSet()
                .rangeByScore(KEY_TIMELINE, fromMs, toMs, 0, Math.max(1, maxResults));
        if (ids == null || ids.isEmpty()) return List.of();
        List<String> idList = new ArrayList<>(ids.size());
        for (Object o : ids) idList.add(o.toString());
        return loadDetails(idList);
    }

    /**
     * 即将到来的活动。
     * @param includePast true 时回溯全部历史；false 时只从 "now - 7 天" 起
     */
    public List<ActivityIndexItem> queryUpcoming(int limit, boolean includePast) {
        long now = System.currentTimeMillis();
        long from = includePast ? Long.MIN_VALUE / 2 : now - 7L * 24 * 3600 * 1000;
        return queryByRange(from, Long.MAX_VALUE / 2, limit);
    }

    /** 时间待定的活动（没有可解析的 startAt） */
    public List<ActivityIndexItem> queryPending(int limit) {
        Set<Object> members = redisTemplate.opsForSet().members(KEY_PENDING);
        if (members == null || members.isEmpty()) return List.of();
        // Set 无序，我们按 articleId 倒序（文章 id 自增 ≈ 发布时间逆序）
        List<String> sorted = members.stream()
                .map(Object::toString)
                .sorted(Comparator.reverseOrder())
                .limit(Math.max(1, limit))
                .toList();
        return loadDetails(sorted);
    }

    // ==================== 辅助 ====================

    private List<ActivityIndexItem> loadDetails(List<String> articleIds) {
        List<ActivityIndexItem> out = new ArrayList<>(articleIds.size());
        Set<String> seen = new LinkedHashSet<>();
        for (String id : articleIds) {
            if (!seen.add(id)) continue;
            Object val = cacheUtil.get(KEY_DETAIL_PREFIX + id);
            if (val == null) continue;
            ActivityIndexItem item = JSON.parseObject(val.toString(), ActivityIndexItem.class);
            if (item != null) out.add(item);
        }
        return out;
    }

    // ==================== DTO ====================

    public record IndexStats(long timelineSize, long pendingSize) {}
}
