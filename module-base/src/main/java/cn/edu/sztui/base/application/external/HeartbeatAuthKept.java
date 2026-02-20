package cn.edu.sztui.base.application.external;

import cn.edu.sztui.base.application.service.AuthService;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.common.cache.dto.ProxySession;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 定时心跳任务
 * <p>
 * 两个职责：
 * <ol>
 *   <li><b>清理过期会话</b>：每 10 分钟遍历 TokenMeta，删除 > 25h 未活跃的条目（替代 Redis TTL）</li>
 *   <li><b>定时爬取</b>：每 50 秒遍历活跃且已登录学校后端的会话，用其 cookie 爬取最新数据</li>
 * </ol>
 */
@Slf4j
@Component
public class HeartbeatAuthKept
{

    @Resource
    private AuthService authService;

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    /**
     * 清理过期会话 —— 每 10 分钟执行
     * <p>
     * 因为 CacheUtil 只有 Hash 操作，无法给单个 field 设 TTL，
     * 所以用定时任务做「应用层 TTL」，删除 lastAccessTime > 25h 的条目。
     */
    @Scheduled(fixedRate = 10 * 60 * 1000)  // 10 分钟
    public void cleanupStaleEntries() {
        try {
            int cleaned = authSessionCacheUtil.cleanupStaleEntries();
            if (cleaned > 0) {
                log.info("定时清理：移除了 {} 个过期会话", cleaned);
            }
        } catch (Exception e) {
            log.error("定时清理过期会话失败", e);
        }
    }

    /**
     * 定时爬取 —— 每 50 秒执行
     * <p>
     * 遍历所有已登录学校后端的会话，用其 cookie 去爬取最新信息（如课表等）。
     * 仅处理 schoolLoggedIn=true 的会话，未登录的跳过。
     */
    @Scheduled(fixedRate = 50000)  // 50 秒
    public void periodicDataFetch() {
        Map<String, ProxySession> sessions = authSessionCacheUtil.getAllSessions();
        if (sessions.isEmpty()) return;

        for (Map.Entry<String, ProxySession> entry : sessions.entrySet()) {
            String openId = entry.getKey();
            ProxySession session = entry.getValue();

            // 只处理已登录学校后端的会话
            if (!session.isSchoolLoggedIn()) continue;

            try {
                // TODO: 用 session.getCookiesJson() 去爬取最新数据
                // 例如：课表更新、成绩更新、通知推送等
                // authService.fetchLatestDataForUser(openId, session);
                log.debug("定时爬取: openId={}", openId);
            } catch (Exception e) {
                log.warn("定时爬取失败: openId={}, error={}", openId, e.getMessage());
                // 单个用户失败不影响其他用户
            }
        }
    }
}