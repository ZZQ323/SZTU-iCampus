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
 * 会话管理控制器（精简版 —— 去除 JWT token 层）
 * <p>
 * 仅保留重置会话功能。
 * Token 相关的 get-token / refresh-token / active 端点已移除。
 */
@Slf4j
@RestController
@RequestMapping("/session")
public class WxAuthController {

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    /**
     * 重置会话（清除 Redis 中的 ProxySession 缓存）
     */
    @PostMapping("/v1/reset")
    public Result resetSession() {
        TokenMessage tokenMessage = UserContext.getContext();
        if (tokenMessage == null || tokenMessage.getOpenId() == null) {
            throw new BusinessException(
                    SysReturnCode.WECHAT_PROXY.getCode(),
                    "未提供身份标识",
                    ResultCodeEnum.UNAUTHORIZED.getCode()
            );
        }

        String openId = tokenMessage.getOpenId();
        authSessionCacheUtil.clearUser(openId);
        log.info("用户 {} 主动重置会话", openId);

        return Result.ok(Map.of("success", true, "message", "会话已重置"));
    }
}
