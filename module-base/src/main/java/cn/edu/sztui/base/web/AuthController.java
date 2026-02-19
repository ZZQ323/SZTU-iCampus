package cn.edu.sztui.base.web;

import cn.edu.sztui.base.application.dto.command.LoginRequestCommand;
import cn.edu.sztui.base.application.service.AuthService;
import cn.edu.sztui.base.application.vo.LoginResultsVo;
import cn.edu.sztui.base.application.vo.LoginStatusVo;
import cn.edu.sztui.common.util.enums.ResultCodeEnum;
import cn.edu.sztui.common.util.enums.SysReturnCode;
import cn.edu.sztui.common.util.exception.BusinessException;
import cn.edu.sztui.common.util.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/**
 * 认证控制器（重构版）
 * <p>
 * 接口拆分说明：
 * <ul>
 *   <li>GET  /auth/v1/status          - 状态查询（轻量，可缓存）</li>
 *   <li>POST /auth/v1/session/init    - 会话初始化（首次/重建 Cookie）</li>
 *   <li>POST /auth/v1/session/refresh - 会话刷新（仅刷新已登录会话）</li>
 *   <li>POST /auth/v1/login           - 登录学校系统</li>
 *   <li>POST /auth/v1/logout          - 登出学校系统</li>
 * </ul>
 */
@Slf4j
@Tag(name = "认证管理", description = "学校登录状态管理相关接口")
@RestController
@RequestMapping("/auth/v1")
public class AuthController {

    @Resource
    private AuthService authService;

    // ==================== 状态查询 ====================

    /**
     * 获取登录状态（轻量级，优先读缓存）
     * <p>
     * 前端可高频调用此接口检查状态，不会每次触发 Playwright。
     * 返回内容包含：是否已登录、可用登录方式、Cookie 是否即将过期。
     */
    @Operation(summary = "获取登录状态", description = "轻量级状态查询，优先读取缓存，30秒TTL")
    @GetMapping("/status")
    public Result getStatus() {
        LoginStatusVo status = authService.getStatus();
        return Result.ok(status);
    }

    /**
     * 检查 session 是否存在（仅检查 Redis，不验证有效性）
     * @deprecated 建议使用 /status 接口
     */
    @Operation(summary = "检查 session 存在性", description = "仅检查 Redis 中是否有记录")
    @Deprecated
    @GetMapping("/status/session")
    public Result getSessionStatus() {
        boolean hasSession = authService.getSessionStatus();
        return Result.ok(Map.of("hasSession", hasSession));
    }

    /**
     * 获取历史登录过的学号列表
     */
    @Operation(summary = "获取历史学号", description = "返回该微信账号曾经登录过的学号列表")
    @GetMapping("/history")
    public Result getHistory() {
        List<String> userIds = authService.getPossibleUsrId();
        return Result.ok(userIds);
    }

    // ==================== 会话管理 ====================

    /**
     * 初始化会话（强制重建 Cookie）
     * <p>
     * 使用场景：
     * <ul>
     *   <li>首次进入需要登录的模块</li>
     *   <li>Cookie 已过期或失效</li>
     *   <li>前端主动请求重新初始化</li>
     * </ul>
     */
    @Operation(summary = "初始化会话", description = "强制重建 Cookie，清除旧状态")
    @PostMapping("/session/init")
    public Result initSession() {
        LoginResultsVo result = authService.initSession();
        return Result.ok(result);
    }

    /**
     * 刷新会话（仅刷新 SESSION_ID）
     * <p>
     * 前置条件：当前已登录学校后端。
     * 如果会话已过期，返回错误码引导前端走登录流程。
     */
    @Operation(summary = "刷新会话", description = "刷新已登录会话的 SESSION_ID，延长有效期")
    @PostMapping("/session/refresh")
    public Result refreshSession() {
        LoginResultsVo result = authService.refreshSession();
        return Result.ok(result);
    }

    /**
     * 原有的 cookie/refresh 接口（兼容旧前端）
     *
     * @deprecated 请使用 /session/init 或 /session/refresh
     */
    @Operation(summary = "刷新 Cookie（旧接口）", description = "兼容旧前端，建议迁移到新接口")
    @Deprecated
    @PostMapping("/cookie/refresh")
    public Result refreshCookie() {
        // 兼容旧逻辑：相当于 initSession
        LoginResultsVo result = authService.initSession();
        return Result.ok(result);
    }

    // ==================== 登录/登出 ====================

    /**
     * 请求发送短信验证码
     */
    @Operation(summary = "请求短信验证码", description = "向指定学号发送短信验证码")
    @PostMapping("/request/sms")
    public Result requestSms(@RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(SysReturnCode.BASE_PROXY.getCode(), "学号不能为空", ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode());
        }
        authService.getSms(userId);
        return Result.ok("success");
    }

    /**
     * 登录学校系统
     */
    @Operation(summary = "登录学校系统", description = "支持短信验证码和密码两种登录方式")
    @PostMapping("/login")
    public Result login(@RequestBody LoginRequestCommand cmd) {
        LoginResultsVo result = authService.loginFrame(cmd);
        return Result.ok(result);
    }

    /**
     * 登出学校系统
     */
    @Operation(summary = "登出学校系统")
    @PostMapping("/logout")
    public Result logout(@RequestBody LoginRequestCommand cmd) {
        LoginResultsVo result = authService.logout(cmd);
        return Result.ok(result);
    }
}