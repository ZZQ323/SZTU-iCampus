package cn.edu.sztui.base.web;

import cn.edu.sztui.base.application.dto.command.LoginRequestCommand;
import cn.edu.sztui.base.application.service.AuthService;
import cn.edu.sztui.base.application.vo.LoginResultsVo;
import cn.edu.sztui.base.application.vo.LoginStatusVo;
import cn.edu.sztui.common.util.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 认证控制器（简化版 —— 去除 JWT token 层）
 * <p>
 * 公开接口（无需认证）：
 * <ul>
 *   <li>POST /auth/v1/session/init    - 初始化会话，获取 loginTypes + cookies</li>
 *   <li>POST /auth/v1/request/sms     - 请求短信验证码</li>
 *   <li>POST /auth/v1/login           - 登录学校系统</li>
 * </ul>
 * <p>
 * 需要认证（header 携带 X-School-Cookies，可选 X-User-Id）：
 * <ul>
 *   <li>GET  /auth/v1/status          - 状态查询</li>
 *   <li>POST /auth/v1/session/refresh - 刷新会话</li>
 *   <li>GET  /auth/v1/history         - 历史学号</li>
 *   <li>POST /auth/v1/logout          - 登出</li>
 * </ul>
 */
@Slf4j
@Tag(name = "认证管理", description = "学校登录状态管理相关接口")
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private AuthService authService;

    // ==================== 公开接口（无需认证） ====================

    /**
     * 初始化会话（公开接口）
     * <p>
     * 访问学校 gateway，获取预登录 cookies + loginTypes。
     * 返回明文 cookies 给前端。
     */
    @Operation(summary = "初始化会话", description = "公开接口，获取预登录 cookies 和可用登录方式")
    @PostMapping("/v1/session/init")
    public Result initSession() {
        LoginResultsVo result = authService.initSession();
        return Result.ok(result);
    }

    /**
     * 请求发送短信验证码（公开接口）
     */
    @Operation(summary = "请求短信验证码")
    @PostMapping("/v1/request/sms")
    public Result requestSms(@RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        String cookiesJson = body.get("cookiesJson");
        if (userId == null || userId.isBlank()) {
            return Result.fail("学号不能为空");
        }
        authService.getSms(userId, cookiesJson);
        return Result.ok("success");
    }

    /**
     * 登录学校系统（公开接口）
     * <p>
     * 请求体需包含 cookiesJson（预登录 cookies）。
     */
    @Operation(summary = "登录学校系统", description = "公开接口，需提供 cookiesJson")
    @PostMapping("/v1/login")
    public Result login(@RequestBody LoginRequestCommand cmd) {
        LoginResultsVo result = authService.loginFrame(cmd);
        return Result.ok(result);
    }

    // ==================== 需要认证的接口 ====================

    /**
     * 获取登录状态
     */
    @Operation(summary = "获取登录状态")
    @GetMapping("/v1/status")
    public Result getStatus() {
        LoginStatusVo status = authService.getStatus();
        return Result.ok(status);
    }

    /**
     * 刷新会话
     */
    @Operation(summary = "刷新会话", description = "使用当前 cookies 刷新学校会话")
    @PostMapping("/v1/session/refresh")
    public Result refreshSession() {
        LoginResultsVo result = authService.refreshSession();
        return Result.ok(result);
    }

    /**
     * 获取历史登录过的学号列表
     */
    @Operation(summary = "获取历史学号")
    @GetMapping("/v1/history")
    public Result getHistory() {
        List<String> userIds = authService.getPossibleUsrId();
        return Result.ok(userIds);
    }

    /**
     * 登出学校系统
     */
    @Operation(summary = "登出学校系统")
    @PostMapping("/v1/logout")
    public Result logout() {
        LoginResultsVo result = authService.logout();
        return Result.ok(result);
    }
}
