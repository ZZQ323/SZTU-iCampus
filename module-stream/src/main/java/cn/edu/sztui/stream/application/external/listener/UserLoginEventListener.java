package cn.edu.sztui.stream.application.external.listener;

import cn.edu.sztui.base.domain.event.UserLoginEvent;
import cn.edu.sztui.stream.application.external.engine.SourceInitTask;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 用户登录事件监听器
 * <p>
 * 改造点：AnnouncementInitTask → SourceInitTask（通用初始化）
 * <p>
 * 文件位置：module-stream/.../application/external/listener/UserLoginEventListener.java
 */
@Slf4j
@Component
public class UserLoginEventListener {

    @Resource
    private SourceInitTask sourceInitTask;

    @Async
    @EventListener
    public void onUserLogin(UserLoginEvent event) {
        log.info("收到用户登录事件: userId={}, realName={}",
                event.getUserId(), event.getRealName());

        try {
            // 触发所有未初始化数据源的全量爬取
            sourceInitTask.triggerInit(event.getUserId());
        } catch (Exception e) {
            log.error("处理登录事件失败: {}", e.getMessage(), e);
        }
    }
}