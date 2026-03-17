package cn.edu.sztui.base.web;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult;
import cn.binarywang.wx.miniapp.util.WxMaConfigHolder;
import cn.edu.sztui.base.application.dto.command.WXLoginDTO;
import cn.edu.sztui.base.application.service.TokenRefreshService;
import cn.edu.sztui.base.application.vo.TokenAuthVo;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.bean.TokenMessage;
import cn.edu.sztui.common.util.enums.ResultCodeEnum;
import cn.edu.sztui.common.util.enums.SysReturnCode;
import cn.edu.sztui.common.util.exception.BusinessException;
import cn.edu.sztui.common.util.jwt.JwtConfig;
import cn.edu.sztui.common.util.result.Result;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;

/**
 * 微信小程序认证控制器
 * <p>
 * 接口：
 * - POST /wx-auth/v1/get-token      首次获取 token（PUBLIC）
 * - POST /wx-auth/v1/refresh-token   刷新过期 token（PUBLIC）
 * - GET  /wx-auth/v1/active          检查 token 有效性（需 token）
 */
@Slf4j
@RestController
@RequestMapping("/wx-auth")
public class WxAuthController {

    @Resource
    private WxMaService wxMaService;
    @Autowired
    private JwtConfig jwtConfig;
    @Autowired
    private TokenRefreshService tokenRefreshService;
    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    /**
     * 检验 token 是否有效
     * <p>能走到这里说明 Filter 已放行，token 一定有效</p>
     */
    @GetMapping("/v1/active")
    public Result isActive() {
        return Result.ok(!Objects.isNull(UserContext.getContext()));
    }

    /**
     * 首次获取 token
     * <p>
     * 小程序启动 → wx.login() 拿 code → 调此接口
     * <p>
     * 流程：code → 微信服务器换 openId/unionId/sessionKey → 生成 JWT + Redis TokenMeta
     */
    @PostMapping("/v1/get-token")
    public Result getToken(@RequestBody WXLoginDTO dto) {
        if (StringUtils.isBlank(dto.getWxCode())) {
            throw new BusinessException(
                    SysReturnCode.WECHAT_PROXY.getCode(),
                    "wx code 不能为空",
                    ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
            );
        }
        try {
            // 1. code 换 session
            WxMaJscode2SessionResult session = wxMaService.getUserService()
                    .getSessionInfo(dto.getWxCode());

            // 2. 委托 TokenRefreshService 完成 JWT + Redis 初始化
            TokenAuthVo ret = tokenRefreshService.initToken(
                    session.getOpenid(),
                    session.getUnionid(),
                    session.getSessionKey()
            );
            return Result.ok(ret);

        } catch (WxErrorException e) {
            log.error("微信 code 换 session 失败: {}", e.getMessage(), e);
            throw new BusinessException(
                    SysReturnCode.WECHAT_PROXY.getCode(),
                    e.getMessage(),
                    ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
            );
        } finally {
            WxMaConfigHolder.remove();
        }
    }

    /**
     * 刷新过期 token
     * <p>
     * 此接口在 PUBLIC_PATHS 中，不经过 Filter 校验。
     * 前端收到 401 后，携带旧 token（标准 Authorization header）+ 新 wx code 调用。
     * <p>
     * 流程：
     * 1. 从 Authorization header 读旧 token
     * 2. 如果提供了新 code，先换 sessionKey
     * 3. 委托 TokenRefreshService 判断滑动窗口 + 签发新 JWT
     */
    @PostMapping("/v1/refresh-token")
    public Result refreshToken(HttpServletRequest request,
                               @RequestBody WXLoginDTO dto) {
        // 1. 从标准 header 读旧 token
        String oldToken = request.getHeader(jwtConfig.getHeader());
        if (StringUtils.isBlank(oldToken)) {
            throw new BusinessException(
                    SysReturnCode.WECHAT_PROXY.getCode(),
                    "缺少旧 token",
                    ResultCodeEnum.UNAUTHORIZED.getCode()
            );
        }

        // 2. 如果有新 code，换新的 sessionKey
        String newSessionKey = null;
        String newUnionId = null;
        if (StringUtils.isNotBlank(dto.getWxCode())) {
            try {
                WxMaJscode2SessionResult session = wxMaService.getUserService()
                        .getSessionInfo(dto.getWxCode());
                newSessionKey = session.getSessionKey();
                newUnionId = session.getUnionid();
            } catch (WxErrorException e) {
                log.warn("刷新 token 时 code 换 session 失败，沿用旧 sessionKey: {}", e.getMessage());
                // 不抛异常，沿用旧的
            } finally {
                WxMaConfigHolder.remove();
            }
        }

        // 3. 委托 TokenRefreshService
        try {
            TokenAuthVo ret = tokenRefreshService.refreshToken(oldToken, newSessionKey, newUnionId);
            return Result.ok(ret);
        } catch (IllegalStateException e) {
            // 不可刷新（超 24h 或无 Redis 数据）
            log.warn("token 刷新失败: {}", e.getMessage());
            throw new BusinessException(
                    SysReturnCode.WECHAT_PROXY.getCode(),
                    e.getMessage(),
                    ResultCodeEnum.UNAUTHORIZED.getCode()
            );
        }
    }

    /**
     * 重置会话（清除 Redis 中的 TokenMeta 和 ProxySession）
     * <p>
     * 使用场景：
     * - 登录会话失效，无法恢复
     * - 用户需要完全重置状态
     * <p>
     * 此接口需要 token（用于获取 openId），调用后会清除该 openId 的所有缓存
     */
    @PostMapping("/v1/reset-session")
    public Result resetSession() {
        TokenMessage tokenMessage = UserContext.getContext();
        if (tokenMessage == null || tokenMessage.getOpenId() == null) {
            throw new BusinessException(
                    SysReturnCode.WECHAT_PROXY.getCode(),
                    "无效的 token",
                    ResultCodeEnum.UNAUTHORIZED.getCode()
            );
        }

        String openId = tokenMessage.getOpenId();

        // 清除该用户的所有 Redis 缓存（TokenMeta + ProxySession）
        authSessionCacheUtil.clearUser(openId);

        log.info("用户 {} 主动重置会话", openId);

        return Result.ok(Map.of("success", true, "message", "会话已重置，请重新初始化"));
    }
}