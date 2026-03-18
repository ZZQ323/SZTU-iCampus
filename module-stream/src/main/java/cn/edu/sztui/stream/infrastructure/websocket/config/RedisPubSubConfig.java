package cn.edu.sztui.stream.infrastructure.websocket.config;

import cn.edu.sztui.stream.infrastructure.websocket.listener.RedisPubMsgListener;
import cn.edu.sztui.stream.infrastructure.websocket.service.WebSocketPushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 配置（WebSocket 跨实例广播）
 * <p>
 * 订阅 channel：ws:push:announcement / ws:push:schedule / ws:push:calendar
 * <p>
 * 当实例 A 发布消息时，实例 B 的 RedisPubMsgListener 收到后调用 pushLocal 推送给本地连接。
 * 单实例部署时，本实例既是 publisher 又是 subscriber，消息会收到一份，
 * 但因为 pushLocal 推送前 broadcast 已经推过本地了，所以 listener 中会做去重
 * （通过判断本实例是否是消息发布者来跳过）。
 * <p>
 * 文件位置：module-stream/.../infrastructure/websocket/config/RedisPubSubConfig.java
 */
@Slf4j
@Configuration
public class RedisPubSubConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisPubMsgListener listener) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // 订阅所有推送 channel
        container.addMessageListener(listener,
                new ChannelTopic(WebSocketPushService.CHANNEL_PREFIX + "announcement"));
        container.addMessageListener(listener,
                new ChannelTopic(WebSocketPushService.CHANNEL_PREFIX + "schedule"));
        container.addMessageListener(listener,
                new ChannelTopic(WebSocketPushService.CHANNEL_PREFIX + "calendar"));

        log.info("Redis Pub/Sub 订阅已注册: ws:push:announcement, ws:push:schedule, ws:push:calendar");
        return container;
    }
}