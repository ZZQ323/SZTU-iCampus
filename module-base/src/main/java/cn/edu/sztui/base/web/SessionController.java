package cn.edu.sztui.base.web;

import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.bean.TokenMessage;
import cn.edu.sztui.common.util.result.Result;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 会话管理控制器
 * <p>
 * 管理 Redis 中的 ProxySession 缓存。
 */
@Slf4j
@RestController
@RequestMapping("/session")
public class SessionController {

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    /**
     * 重置会话（清除 Redis 中的 ProxySession 缓存）
     * <p>
     * <b>设计语义</b>：reset 是"用户状态彻底乱了想从零开始"的紧急逃生口。在这种
     * 场景下前端的 cookies 可能已残缺、userId 可能已丢，强制要求 userId 反而
     * 让用户无法自救。所以：
     * <ul>
     *   <li>有 userId（X-User-Id header 或 cookies 里能推出）→ 清 Redis ProxySession</li>
     *   <li>没 userId → 后端无操作，但仍返回成功，让前端继续走 clearAll + initSession 流程</li>
     * </ul>
     * <p>
     * 此端点已加入 {@code CookieAuthFilter.PUBLIC_PATHS}，cookies 全空也能调通。
     */
    @PostMapping("/v1/reset")
    public Result resetSession() {
        TokenMessage tokenMessage = UserContext.getContext();
        String userId = tokenMessage == null ? null : tokenMessage.getUserId();
        if (userId == null || userId.isBlank()) {
            log.info("[reset] 收到无 userId 的 reset 请求 → 后端无操作（前端自行清状态）");
            return Result.ok(Map.of("success", true, "message", "会话已重置（无 userId，后端无操作）"));
        }
        authSessionCacheUtil.clearUser(userId);
        log.info("[reset] 用户 {} 主动重置会话，已清 Redis ProxySession", userId);
        return Result.ok(Map.of("success", true, "message", "会话已重置"));
    }
}
