package cn.edu.sztui.base.web;

import cn.edu.sztui.base.application.dto.command.LoginRequestCommand;
import cn.edu.sztui.base.application.service.AuthService;
import cn.edu.sztui.common.util.result.Result;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController
{
    @Resource
    private AuthService authService;

    /**
     * 检测 unionID的 状态
     * @return
     */
    @GetMapping("/v1/status/session")
    public Result getSessionStatus() {
        return Result.ok(authService.getSessionStatus());
    }

    @GetMapping("/v1/history")
    public Result getPossibleUsrId() {
        return Result.ok(authService.getPossibleUsrId());
    }

    /**
     * 验证码过期，再次请求
     * @return
     */
    @PostMapping("/v1/request/sms")
    public Result getSms(@RequestBody LoginRequestCommand request) {
        authService.getSms(request.getUserId());
        return Result.ok("success");
    }

    /**
     * 初始化新的cookie
     * @return
     */
    @PostMapping("/v1/cookie/refresh")
    public Result refresh(){
        return Result.ok(authService.init());
    }

    /**
     * 网关验证码登录
     * @param request
     * @return
     */
    @PostMapping("/v1/login")
    public Result loginSms(@RequestBody LoginRequestCommand request) {
        return Result.ok(authService.loginFrame(request));
    }

    /**
     * 登出
     * @param request
     * @return
     */
     @PostMapping("/v1/logout")
     public Result logout(@RequestBody LoginRequestCommand request) {
         return Result.ok(authService.logout(request));
     }
}
