package cn.edu.sztui.stream.application.external.listener;

import cn.edu.sztui.base.infrastructure.event.UserLoginEvent;
import cn.edu.sztui.stream.application.external.announcement.AnnouncementInitTask;
import cn.edu.sztui.stream.infrastructure.util.cache.AnnouncementCacheUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 用户登录事件监听器
 * 
 * 监听用户登录事件，触发公告系统初始化
 * 
 * 文件位置：module-stream/src/main/java/cn/edu/sztui/stream/application/external/listener/UserLoginEventListener.java
 */
@Slf4j
@Component
public class UserLoginEventListener {

    @Resource
    private AnnouncementInitTask announcementInitTask;

    @Resource
    private AnnouncementCacheUtil announcementCacheUtil;

    /**
     * 监听用户登录事件
     */
    @Async
    @EventListener
    public void onUserLogin(UserLoginEvent event) {
        log.info("收到用户登录事件: openId={}, userId={}, realName={}",
                event.getOpenId(), event.getUserId(), event.getRealName());

        try {
            // 设置活跃 Cookie 来源
            if (!announcementCacheUtil.hasActiveSource()) {
                announcementCacheUtil.setActiveSourceOpenId(event.getOpenId());
                log.debug("已设置活跃 Cookie 来源: {}", event.getOpenId());
            }

            // 触发初始化（内部会检查是否已初始化）
            announcementInitTask.triggerInit(event.getOpenId());

        } catch (Exception e) {
            log.error("处理登录事件失败: openId={}, error={}", 
                    event.getOpenId(), e.getMessage(), e);
        }
    }
}
