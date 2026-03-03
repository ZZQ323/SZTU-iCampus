package cn.edu.sztui.stream.application.task;

import cn.edu.sztui.stream.infrastructure.cookie.CookieSourceManager;
import cn.edu.sztui.stream.infrastructure.sse.SseEmitterManager;
import cn.edu.sztui.stream.infrastructure.stream.StreamPublisher;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 抽象爬取任务
 * <p>
 * 提供公共数据爬取的模板方法，子类实现具体逻辑
 * <p>
 * 适用于：公告、活动日历等"全局共享"的数据
 * 不适用于：课表等"用户私有"的数据
 */
@Slf4j
public abstract class AbstractCrawlTask {

    @Resource
    protected CookieSourceManager cookieSourceManager;

    @Resource
    protected SseEmitterManager sseEmitterManager;

    @Resource
    protected StreamPublisher streamPublisher;

    /**
     * 执行爬取任务（模板方法）
     * <p>
     * 流程：
     * <ol>
     *   <li>检查系统是否可运行（有有效Cookie）</li>
     *   <li>获取 Cookie 来源</li>
     *   <li>执行具体的爬取逻辑</li>
     *   <li>处理异常</li>
     * </ol>
     */
    protected void executeCrawl() {
        String taskName = getTaskName();

        // 1. 检查系统是否可运行
        if (!cookieSourceManager.isSystemOperational()) {
            log.debug("[{}] 系统不可运行（无有效Cookie），跳过本次任务", taskName);
            return;
        }

        // 2. 获取 Cookie 来源
        String sourceOpenId = cookieSourceManager.getAvailableSource();
        if (sourceOpenId == null) {
            log.warn("[{}] 无可用 Cookie 来源，跳过本次任务", taskName);
            return;
        }

        try {
            log.info("[{}] 开始执行，Cookie 来源: {}", taskName, maskOpenId(sourceOpenId));

            // 3. 执行具体的爬取逻辑
            doCrawl(sourceOpenId);

            log.info("[{}] 执行完成", taskName);

        } catch (Exception e) {
            log.error("[{}] 执行异常: {}", taskName, e.getMessage(), e);
            handleCrawlError(e);
        }
    }

    /**
     * 获取任务名称（用于日志）
     */
    protected abstract String getTaskName();

    /**
     * 获取订阅的 topic
     */
    protected abstract String getTopicName();

    /**
     * 执行具体的爬取逻辑
     *
     * @param sourceOpenId Cookie 来源的 openId
     */
    protected abstract void doCrawl(String sourceOpenId);

    /**
     * 检查是否有订阅者
     *
     * @return true 表示有用户订阅了该 topic
     */
    protected boolean hasSubscribers() {
        return sseEmitterManager.getConnectionCount(getTopicName()) > 0;
    }

    /**
     * 处理爬取错误（子类可覆盖）
     * <p>
     * 默认行为：标记当前 Cookie 来源可能失效
     */
    protected void handleCrawlError(Exception e) {
        // 如果是认证相关错误，标记来源失效
        String message = e.getMessage();
        if (message != null && (
                message.contains("401") ||
                        message.contains("登录") ||
                        message.contains("会话过期"))) {
            cookieSourceManager.markSourceInvalid();
        }
    }

    /**
     * 脱敏处理 openId
     */
    private String maskOpenId(String openId) {
        if (openId == null) return "null";
        if (openId.length() <= 8) return "***";
        return openId.substring(0, 4) + "***" + openId.substring(openId.length() - 4);
    }
}