package cn.edu.sztui.base.domain.event;

import lombok.Data;
import org.springframework.context.ApplicationEvent;

/**
 * 用户登录事件
 * <p>
 * 当用户成功登录学校系统后发布此事件
 * 用于触发公告系统初始化、缓存预热等操作
 */
@Data
public class UserLoginEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    /**
     * 微信 OpenId
     */
    private final String openId;

    /**
     * 学号
     */
    private final String userId;

    /**
     * 真实姓名
     */
    private final String realName;

    /**
     * 登录时间戳
     */
    private final Long loginTime;

    public UserLoginEvent(Object source, String openId, String userId, String realName) {
        super(source);
        this.openId = openId;
        this.userId = userId;
        this.realName = realName;
        this.loginTime = System.currentTimeMillis();
    }

    public UserLoginEvent(Object source, String openId) {
        this(source, openId, null, null);
    }
}