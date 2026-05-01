package cn.edu.sztui.common.cache.util;

import cn.edu.sztui.common.cache.redis.RedisKeyGenerator;
import cn.edu.sztui.common.cache.util.service.CacheService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis 访问唯一入口。
 * <p>
 * <b>硬规则</b>：项目内所有 Redis 读写**必须**经此类（某些 Redis Stream listener 由
 * Spring Data Redis SDK 直接持有原始 streamKey，是显式例外，参考 StreamPublisher）。
 * <ul>
 *   <li>每个公共方法把入参 {@code key} 通过 {@code redisKeyGenerator.generate("cache:" + key)}
 *       归一化前缀（实际形如 {@code dev:sztu:cache:<key>}）。</li>
 *   <li>调用方传入的 {@code key} 必须是 <b>raw</b> 形式（如 {@code "info:foo:meta"}），
 *       <b>不要</b>事先用 {@code redisKeyGenerator} 或其他方式预加前缀，否则会双前缀。</li>
 *   <li>String / Hash 委托给 {@link CacheService}；ZSet / Set / List 直接走
 *       {@link RedisTemplate}，因为 {@link CacheService} 这部分 API 残缺或有 bug
 *       （如 {@code zSSet} 用了 {@code opsForSet} 且硬编码 score=2.0）。</li>
 * </ul>
 */
@Component
public class CacheUtil {
    @Resource
    RedisKeyGenerator redisKeyGenerator;
    @Resource
    private CacheService cacheService;
    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    private String wrap(String key) {
        return this.redisKeyGenerator.generate("cache:" + key);
    }

    // ==================== String 操作（⭐ 新增） ====================

    /**
     * 设置值（无过期）
     */
    public boolean set(String key, Object value) {
        String rkey = this.redisKeyGenerator.generate("cache:" + key);
        return this.cacheService.set(rkey, value);
    }

    /**
     * 设置值 + 过期时间（秒）
     */
    public boolean set(String key, Object value, long timeSeconds) {
        String rkey = this.redisKeyGenerator.generate("cache:" + key);
        return this.cacheService.set(rkey, value, timeSeconds);
    }

    /**
     * 获取值
     */
    public Object get(String key) {
        String rkey = this.redisKeyGenerator.generate("cache:" + key);
        return this.cacheService.get(rkey);
    }

    /**
     * 判断 key 是否存在
     */
    public boolean hasKey(String key) {
        String rkey = this.redisKeyGenerator.generate("cache:" + key);
        return this.cacheService.hasKey(rkey);
    }

    /**
     * 设置过期时间（秒）
     */
    public boolean expire(String key, long timeSeconds) {
        String rkey = this.redisKeyGenerator.generate("cache:" + key);
        return this.cacheService.expire(rkey, timeSeconds);
    }

    /**
     * 按前缀扫描 key（⭐ 用于替代 getAllTokenMetas / getAllSessions）
     * <p>
     * 注意：生产环境大数据量时应使用 SCAN 代替 KEYS，
     * 但 CacheService 如果只有 keys() 方法，先用着。
     */
    public Set<String> keys(String pattern) {
        String rkey = this.redisKeyGenerator.generate("cache:" + pattern);
        return this.cacheService.keys(rkey);
    }

    // ==================== Hash 操作（原有） ====================

    public Object hget(String key, String item) {
        String rkey = this.redisKeyGenerator.generate("cache:" + key);
        return this.cacheService.hget(rkey, item);
    }

    public List<Object> hget(String key, Collection<Object> hKeys) {
        String rkey = this.redisKeyGenerator.generate("cache:" + key);
        return this.cacheService.hget(rkey, hKeys);
    }

    public Map<Object, Object> hmget(String key) {
        String rkey = this.redisKeyGenerator.generate("cache:" + key);
        return this.cacheService.hmget(rkey);
    }

    public boolean hmset(String key, Map<String, Object> map) {
        String rkey = this.redisKeyGenerator.generate("cache:" + key);
        return this.cacheService.hmset(rkey, map);
    }

    public boolean hmset(String key, Map<String, Object> map, long time) {
        String rkey = this.redisKeyGenerator.generate("cache:" + key);
        return this.cacheService.hmset(rkey, map, time);
    }

    public boolean hset(String key, String item, Object value) {
        String rkey = this.redisKeyGenerator.generate("cache:" + key);
        return this.cacheService.hset(rkey, item, value);
    }

    public boolean hset(String key, String item, Object value, long time) {
        String rkey = this.redisKeyGenerator.generate("cache:" + key);
        return this.cacheService.hset(rkey, item, value, time);
    }

    public void hdel(String key, Object... item) {
        String rkey = this.redisKeyGenerator.generate("cache:" + key);
        this.cacheService.hdel(rkey, item);
    }

    public boolean hHasKey(String key, String item) {
        String rkey = this.redisKeyGenerator.generate("cache:" + key);
        return this.cacheService.hHasKey(rkey, item);
    }

    public void del(String key) {
        String rkey = this.redisKeyGenerator.generate("cache:" + key);
        this.cacheService.del(new String[]{rkey});
    }

    // ==================== ZSet 操作 ====================

    /** 向 ZSet 添加元素（score 排序）；存在则更新 score */
    public Boolean zAdd(String key, Object value, double score) {
        return this.redisTemplate.opsForZSet().add(wrap(key), value, score);
    }

    /** 移除 ZSet 元素 */
    public Long zRem(String key, Object... values) {
        return this.redisTemplate.opsForZSet().remove(wrap(key), values);
    }

    /** ZSet 元素总数 */
    public Long zCard(String key) {
        return this.redisTemplate.opsForZSet().zCard(wrap(key));
    }

    /** 按下标升序读 [start, end] */
    public Set<Object> zRange(String key, long start, long end) {
        return this.redisTemplate.opsForZSet().range(wrap(key), start, end);
    }

    /** 按下标降序读 [start, end] */
    public Set<Object> zReverseRange(String key, long start, long end) {
        return this.redisTemplate.opsForZSet().reverseRange(wrap(key), start, end);
    }

    /** 按 score 范围 [min, max] 升序读全部 */
    public Set<Object> zRangeByScore(String key, double min, double max) {
        return this.redisTemplate.opsForZSet().rangeByScore(wrap(key), min, max);
    }

    /** 按 score 范围分页（offset/count） */
    public Set<Object> zRangeByScore(String key, double min, double max, long offset, long count) {
        return this.redisTemplate.opsForZSet().rangeByScore(wrap(key), min, max, offset, count);
    }

    /** 取元素 score；元素不存在返回 null */
    public Double zScore(String key, Object value) {
        return this.redisTemplate.opsForZSet().score(wrap(key), value);
    }

    // ==================== Set 操作 ====================

    /** 添加 Set 元素，返回新增数量 */
    public Long sAdd(String key, Object... values) {
        return this.redisTemplate.opsForSet().add(wrap(key), values);
    }

    /** 移除 Set 元素，返回移除数量 */
    public Long sRem(String key, Object... values) {
        return this.redisTemplate.opsForSet().remove(wrap(key), values);
    }

    /** Set 全量成员 */
    public Set<Object> sMembers(String key) {
        return this.redisTemplate.opsForSet().members(wrap(key));
    }

    /** Set 成员存在性 */
    public Boolean sIsMember(String key, Object value) {
        return this.redisTemplate.opsForSet().isMember(wrap(key), value);
    }

    /** Set 元素总数 */
    public Long sCard(String key) {
        return this.redisTemplate.opsForSet().size(wrap(key));
    }

    // ==================== List 操作 ====================

    /** 左侧 push（队首入），返回 push 后总长度 */
    public Long lLeftPush(String key, Object value) {
        return this.redisTemplate.opsForList().leftPush(wrap(key), value);
    }

    /** 右侧 push（队尾入），返回 push 后总长度 */
    public Long lRightPush(String key, Object value) {
        return this.redisTemplate.opsForList().rightPush(wrap(key), value);
    }

    /** 按下标读 [start, end] */
    public List<Object> lRange(String key, long start, long end) {
        return this.redisTemplate.opsForList().range(wrap(key), start, end);
    }

    /** 裁剪到 [start, end] 区间，超出部分丢弃 */
    public void lTrim(String key, long start, long end) {
        this.redisTemplate.opsForList().trim(wrap(key), start, end);
    }

    /** List 长度 */
    public Long lSize(String key) {
        return this.redisTemplate.opsForList().size(wrap(key));
    }
}