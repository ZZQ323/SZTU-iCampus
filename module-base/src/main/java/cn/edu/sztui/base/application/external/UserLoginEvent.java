package cn.edu.sztui.base.application.external;

import org.springframework.context.ApplicationEvent;

/**
 * 用户登录成功事件
 *
 * 用于解耦 module-base 和 module-stream 之间的依赖
 */
public class UserLoginEvent extends ApplicationEvent {

    private final String openId;
    private final String userId;
    private final String realName;

    public UserLoginEvent(Object source, String openId, String userId, String realName) {
        super(source);
        this.openId = openId;
        this.userId = userId;
        this.realName = realName;
    }

    public String getOpenId() {
        return openId;
    }

    public String getUserId() {
        return userId;
    }

    public String getRealName() {
        return realName;
    }
}
