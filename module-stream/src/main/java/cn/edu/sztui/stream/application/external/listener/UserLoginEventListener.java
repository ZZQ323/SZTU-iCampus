package cn.edu.sztui.stream.application.external.listener;


import cn.edu.sztui.base.application.external.UserLoginEvent;
import cn.edu.sztui.stream.application.external.announcement.AnnouncementInitTask;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 用户登录事件监听器
 *
 * 监听用户登录成功事件，触发相关初始化任务
 */
@Slf4j
@Component
public class UserLoginEventListener {

    @Resource
    private AnnouncementInitTask announcementInitTask;

    /**
     * 处理用户登录事件
     *
     * 使用 @Async 异步执行，不阻塞登录流程
     */
    @Async
    @EventListener
    public void onUserLogin(UserLoginEvent event) {
        String openId = event.getOpenId();
        log.info("收到用户登录事件: openId={}, userId={}", openId, event.getUserId());

        // 触发公告系统初始化（如果尚未初始化）
        if (!announcementInitTask.isInitialized() && !announcementInitTask.isInitializing()) {
            log.info("触发公告系统初始化: openId={}", openId);
            announcementInitTask.triggerInit(openId);
        } else {
            log.debug("公告系统已初始化或正在初始化，跳过");
        }
    }
}
