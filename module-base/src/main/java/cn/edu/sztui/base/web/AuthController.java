package cn.edu.sztui.base.web;

import cn.edu.sztui.base.application.dto.command.LoginRequestCommand;
import cn.edu.sztui.base.application.service.AuthService;
import cn.edu.sztui.base.application.vo.LoginResultsVo;
import cn.edu.sztui.base.application.vo.LoginStatusVo;
import cn.edu.sztui.common.util.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 认证控制器
 * <p>
 * Cookies 通过 header 收发：
 * - 前端通过 X-School-Cookies header 发送 cookies
 * - 后端通过 X-Set-Cookies response header 返回更新后的 cookies
 * - CookieResponseFilter 统一暴露 Access-Control-Expose-Headers
 * <p>
 * 公开接口（cookie 可选）：
 * <ul>
 *   <li>POST /auth/v1/session/init    - 初始化会话，获取预登录 cookies 和 loginTypes</li>
 * </ul>
 * <p>
 * 登录流程接口（需要 initSession 返回的 cookies）：
 * <ul>
 *   <li>POST /auth/v1/request/sms     - 请求短信验证码（需 initSession cookies）</li>
 *   <li>POST /auth/v1/login           - 登录学校系统（需 requestSms cookies）</li>
 * </ul>
 * <p>
 * 需要认证（header 必须携带 X-School-Cookies）：
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

    public static final String HEADER_SET_COOKIES = "X-Set-Cookies";

    @Resource
    private AuthService authService;

    // ==================== 公开接口（cookie 可选） ====================

    @Operation(summary = "初始化会话", description = "公开接口，获取预登录 cookies 和可用登录方式")
    @PostMapping("/v1/session/init")
    public Result initSession(HttpServletResponse response) {
        LoginResultsVo result = authService.initSession();
        // header + body 双保险：header 给自动拦截器，body 给手动兜底
        setCookieHeader(response, result);
        return Result.ok(result);
    }

    // ==================== 登录流程接口（需要 initSession 返回的 cookies） ====================

    @Operation(summary = "请求短信验证码", description = "需要 initSession 返回的 cookies")
    @PostMapping("/v1/request/sms")
    public Result requestSms(@RequestBody Map<String, String> body, HttpServletResponse response) {
        String userId = body.get("userId");
        if (userId == null || userId.isBlank()) {
            return Result.error("学号不能为空");
        }
        String cookiesJson = authService.getSms(userId);
        if (cookiesJson != null) {
            response.setHeader(HEADER_SET_COOKIES, cookiesJson);
        }
        return Result.ok("success");
    }

    @Operation(summary = "登录学校系统", description = "需要 requestSms 返回的 cookies")
    @PostMapping("/v1/login")
    public Result login(@RequestBody LoginRequestCommand cmd, HttpServletResponse response) {
        LoginResultsVo result = authService.loginFrame(cmd);
        // header + body 双保险
        setCookieHeader(response, result);
        return Result.ok(result);
    }

    // ==================== 需要认证的接口 ====================

    @Operation(summary = "获取登录状态")
    @GetMapping("/v1/status")
    public Result getStatus(HttpServletResponse response) {
        LoginStatusVo status = authService.getStatus();
        // ⭐ 将 doRefreshCookies 拿到的新鲜 cookies 通过 header 返回给前端
        if (status.getCookiesJson() != null) {
            response.setHeader(HEADER_SET_COOKIES, status.getCookiesJson());
            status.setCookiesJson(null);  // header 已设，body 不需要
        }
        return Result.ok(status);
    }

    @Operation(summary = "刷新会话", description = "使用当前 cookies 刷新学校会话")
    @PostMapping("/v1/session/refresh")
    public Result refreshSession(HttpServletResponse response) {
        LoginResultsVo result = authService.refreshSession();
        extractCookiesToHeader(response, result);
        return Result.ok(result);
    }

    @Operation(summary = "获取历史学号")
    @GetMapping("/v1/history")
    public Result getHistory() {
        List<String> userIds = authService.getPossibleUsrId();
        return Result.ok(userIds);
    }

    @Operation(summary = "登出学校系统")
    @PostMapping("/v1/logout")
    public Result logout(HttpServletResponse response) {
        LoginResultsVo result = authService.logout();
        extractCookiesToHeader(response, result);
        return Result.ok(result);
    }

    // ==================== 工具方法 ====================

    /**
     * 设置 X-Set-Cookies header，保留 body 中的 cookiesJson（header + body 双保险）
     * <p>
     * 用于 initSession / login 等需要前端一定能拿到 cookies 的接口。
     * 小程序 uni.request 可能无法读取自定义响应头（即使有 Access-Control-Expose-Headers），
     * 所以 body 作为兜底。
     */
    private void setCookieHeader(HttpServletResponse response, LoginResultsVo result) {
        if (result != null && result.getCookiesJson() != null) {
            response.setHeader(HEADER_SET_COOKIES, result.getCookiesJson());
        }
    }

    /**
     * 设置 X-Set-Cookies header 并清空 body 中的 cookiesJson
     * <p>
     * 用于 refresh / logout 等已有 cookies 的接口（不需要 body 兜底）
     */
    private void extractCookiesToHeader(HttpServletResponse response, LoginResultsVo result) {
        if (result != null && result.getCookiesJson() != null) {
            response.setHeader(HEADER_SET_COOKIES, result.getCookiesJson());
            result.setCookiesJson(null);
        }
    }
}
