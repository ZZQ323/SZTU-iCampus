package cn.edu.sztui.common.cache.util;

import cn.edu.sztui.common.cache.redis.RedisKeyGenerator;
import cn.edu.sztui.common.cache.util.service.CacheService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class CacheUtil {
    @Resource
    RedisKeyGenerator redisKeyGenerator;
    @Resource
    private CacheService cacheService;

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
}