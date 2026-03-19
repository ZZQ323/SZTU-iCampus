package cn.edu.sztui.common.cache.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 序列化配置
 * <p>
 * ⭐ 解决问题：默认 JdkSerializationRedisSerializer 导致 key/value 全是二进制乱码
 * <p>
 * 改后效果：
 * key:   dev:sztu:cache:info:source:gwt-jiaowu:system  （可读字符串）
 * value: {"lastCrawlTime": 1773899201291, "initialized": true}  （JSON）
 * <p>
 * ⚠️ 重要：修改序列化器后，旧的二进制 key 无法被读取。
 * 部署前需要清空 Redis（FLUSHDB）或删除相关 key。
 * <p>
 * 放置位置：module-common/.../cache/redis/RedisConfig.java
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // ==================== Key 序列化：String ====================
        // key 永远是字符串，用 StringRedisSerializer
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // ==================== Value 序列化：JSON ====================
        // value 用 GenericJackson2JsonRedisSerializer（自带类型信息，支持反序列化回原始类型）
        GenericJackson2JsonRedisSerializer jsonSerializer = createJsonSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 创建 JSON 序列化器
     * <p>
     * 使用默认的 GenericJackson2JsonRedisSerializer，它会：
     * - 存储时加 @class 字段（用于反序列化时还原类型）
     * - 支持复杂对象（Map、List、自定义 POJO）
     * - 简单值（String/Integer/Long）序列化为 JSON 原始值
     * <p>
     * 如果不想要 @class 字段（纯净 JSON），可以换成：
     * new Jackson2JsonRedisSerializer<>(Object.class)
     * 但反序列化时所有对象都会变成 LinkedHashMap，需要手动转换。
     */
    private GenericJackson2JsonRedisSerializer createJsonSerializer() {
        return new GenericJackson2JsonRedisSerializer();
    }
}