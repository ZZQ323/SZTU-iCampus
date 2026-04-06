package cn.edu.sztui.base.application.dto.command;

import cn.edu.sztui.base.domain.model.login.LoginType;
import lombok.Data;

@Data
public class LoginRequestCommand {
    private String userId;
    private String password;
    private String smsCode;
    private LoginType loginType;
    /** 前端传来的预登录 cookies JSON */
    private String cookiesJson;
}
