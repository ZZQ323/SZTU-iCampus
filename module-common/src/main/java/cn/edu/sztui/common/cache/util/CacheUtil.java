package cn.edu.sztui.common.cache.util;

import cn.edu.sztui.common.cache.redis.RedisKeyGenerator;
import cn.edu.sztui.common.cache.util.service.CacheService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
public class CacheUtil
{
    @Resource
    RedisKeyGenerator redisKeyGenerator;
    @Resource
    private CacheService cacheService;

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
