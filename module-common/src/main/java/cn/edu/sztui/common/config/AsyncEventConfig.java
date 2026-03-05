package cn.edu.sztui.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 异步事件配置
 * <p>
 * 使 Spring 事件支持异步处理
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncEventConfig {

    /**
     * 配置异步事件多播器
     * <p>
     * 使 ApplicationEvent 的监听器可以异步执行
     */
    @Bean(name = "applicationEventMulticaster")
    public ApplicationEventMulticaster simpleApplicationEventMulticaster() {
        SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster();
        multicaster.setTaskExecutor(new SimpleAsyncTaskExecutor("event-"));
        log.info("已配置异步事件多播器");
        return multicaster;
    }
}