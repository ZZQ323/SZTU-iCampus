package cn.edu.sztui.stream.application.external.listener;

import cn.edu.sztui.base.application.external.UserLoginEvent;
import cn.edu.sztui.base.infrastructure.util.cache.AnnouncementCacheUtil;
import cn.edu.sztui.stream.application.external.announcement.AnnouncementInitTask;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 用户登录事件监听器
 * 
 * 监听 UserLoginEvent，触发：
 * 1. 设置活跃 Cookie 来源（用于后续爬取）
 * 2. 公告系统初始化（首次登录用户）
 * 
 * 【重要】：
 * - 使用 @Async 异步执行，不阻塞登录流程
 * - 初始化任务内部有幂等检查，不会重复执行
 */
@Slf4j
@Component
public class UserLoginEventListener {

    @Resource
    private AnnouncementInitTask announcementInitTask;

    @Resource
    private AnnouncementCacheUtil announcementCacheUtil;

    /**
     * 处理用户登录事件
     * 
     * @param event 登录事件（包含 openId, userId, realName）
     */
    @Async
    @EventListener
    public void onUserLogin(UserLoginEvent event) {
        log.info("收到用户登录事件: openId={}, userId={}, realName={}",
                event.getOpenId(), event.getUserId(), event.getRealName());

        try {
            // 1. 设置活跃 Cookie 来源
            // 后续的增量爬取和其他需要 Cookie 的操作会使用这个 openId
            announcementCacheUtil.setActiveSourceOpenId(event.getOpenId());
            log.debug("已设置活跃 Cookie 来源: {}", event.getOpenId());

            // 2. 触发公告系统初始化
            // 内部会判断是否已初始化，已初始化则跳过
            announcementInitTask.triggerInit(event.getOpenId());

        } catch (Exception e) {
            log.error("处理登录事件失败: openId={}, error={}", 
                    event.getOpenId(), e.getMessage(), e);
        }
    }
}
