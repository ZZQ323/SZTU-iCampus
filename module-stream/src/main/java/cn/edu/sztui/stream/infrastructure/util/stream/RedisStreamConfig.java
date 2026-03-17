package cn.edu.sztui.stream.infrastructure.util.stream;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;

/**
 * Redis Streams 配置
 * 
 * 配置消费者容器和消费者组
 * 
 * 文件位置：module-stream/src/main/java/cn/edu/sztui/stream/infrastructure/util/stream/RedisStreamConfig.java
 */
@Slf4j
@Configuration
public class RedisStreamConfig {
    
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    
    @Resource
    private RedisConnectionFactory redisConnectionFactory;
    
    @Resource
    private StreamConsumer streamConsumer;
    
    /**
     * 当前消费者名称 (使用主机名+UUID保证唯一)
     */
    private String consumerName;
    
    @PostConstruct
    public void init() {
        // 生成唯一的消费者名称
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            consumerName = hostname + "-" + UUID.randomUUID().toString().substring(0, 8);
        } catch (Exception e) {
            consumerName = "consumer-" + UUID.randomUUID().toString().substring(0, 8);
        }
        log.info("Redis Stream Consumer 名称: {}", consumerName);
        
        // 初始化 Streams 和消费者组
        initStreamAndGroup(StreamKeys.STREAM_SCHEDULE, StreamKeys.GROUP_SCHEDULE);
        initStreamAndGroup(StreamKeys.STREAM_ANNOUNCEMENT, StreamKeys.GROUP_ANNOUNCEMENT);
        initStreamAndGroup(StreamKeys.STREAM_CALENDAR, StreamKeys.GROUP_CALENDAR);
    }
    
    /**
     * 初始化 Stream 和消费者组
     * 
     * 如果 Stream 不存在则创建，如果消费者组不存在则创建
     */
    private void initStreamAndGroup(String streamKey, String groupName) {
        try {
            // 检查 Stream 是否存在，不存在则通过 XGROUP CREATE 创建
            // MKSTREAM 选项会在 Stream 不存在时自动创建
            stringRedisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), groupName);
            log.info("创建消费者组成功 - stream: {}, group: {}", streamKey, groupName);
        } catch (Exception e) {
            // 消费者组已存在时会抛异常，忽略即可
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("消费者组已存在 - stream: {}, group: {}", streamKey, groupName);
            } else {
                log.warn("初始化 Stream/Group 异常: {} - {}", streamKey, e.getMessage());
            }
        }
    }
    
    /**
     * 创建 Stream 消息监听容器
     */
    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> streamMessageListenerContainer() {
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        // 每次拉取的消息数量
                        .batchSize(10)
                        // 拉取消息的超时时间 (阻塞读取)
                        .pollTimeout(Duration.ofSeconds(2))
                        .build();
        
        return StreamMessageListenerContainer.create(redisConnectionFactory, options);
    }
    
    /**
     * 订阅课表更新流
     */
    @Bean
    public Subscription scheduleStreamSubscription(
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container) {
        
        Subscription subscription = container.receive(
                Consumer.from(StreamKeys.GROUP_SCHEDULE, consumerName),
                StreamOffset.create(StreamKeys.STREAM_SCHEDULE, ReadOffset.lastConsumed()),
                streamConsumer::onScheduleMessage
        );
        
        container.start();
        log.info("课表 Stream 订阅启动 - consumer: {}", consumerName);
        return subscription;
    }
    
    /**
     * 订阅公告更新流
     */
    @Bean
    public Subscription announcementStreamSubscription(
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container) {
        
        Subscription subscription = container.receive(
                Consumer.from(StreamKeys.GROUP_ANNOUNCEMENT, consumerName),
                StreamOffset.create(StreamKeys.STREAM_ANNOUNCEMENT, ReadOffset.lastConsumed()),
                streamConsumer::onAnnouncementMessage
        );
        
        log.info("公告 Stream 订阅启动 - consumer: {}", consumerName);
        return subscription;
    }
    
    public String getConsumerName() {
        return consumerName;
    }
}
