package cn.edu.sztui.base.web;

import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.bean.TokenMessage;
import cn.edu.sztui.common.util.enums.ResultCodeEnum;
import cn.edu.sztui.common.util.enums.SysReturnCode;
import cn.edu.sztui.common.util.exception.BusinessException;
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
     */
    @PostMapping("/v1/reset")
    public Result resetSession() {
        TokenMessage tokenMessage = UserContext.getContext();
        if (tokenMessage == null || tokenMessage.getUserId() == null) {
            throw new BusinessException(
                    SysReturnCode.WECHAT_PROXY.getCode(),
                    "未提供身份标识",
                    ResultCodeEnum.UNAUTHORIZED.getCode()
            );
        }

        String userId = tokenMessage.getUserId();
        authSessionCacheUtil.clearUser(userId);
        log.info("用户 {} 主动重置会话", userId);

        return Result.ok(Map.of("success", true, "message", "会话已重置"));
    }
}
