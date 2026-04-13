package cn.edu.sztui.common.util.auth;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 用户请求携带 Cookie 时发布的事件
 * <p>
 * 由 CookieAuthFilter 发布，base 模块异步监听并将 cookies 写入 Redis 供爬虫引擎使用。
 */
@Getter
public class CookieAccessEvent extends ApplicationEvent {

    private final String userId;
    private final String cookiesJson;

    public CookieAccessEvent(Object source, String userId, String cookiesJson) {
        super(source);
        this.userId = userId;
        this.cookiesJson = cookiesJson;
    }
}
