package cn.edu.sztui.base.domain.event;

import lombok.Data;
import org.springframework.context.ApplicationEvent;

/**
 * 用户登出事件。
 * <p>
 * AuthServiceImpl.logout 在清空 Redis cookies + 翻 schoolLoggedIn=false 之后发布。
 * 下游（module-stream）监听后做：
 * <ol>
 *   <li>主动踢断该 user 的 WS 连接（避免 in-flight 推送把 cookie 复活到前端）</li>
 *   <li>清掉如果当前 active source 是这个 user 的标记</li>
 * </ol>
 * <p>
 * 为什么单独事件而不是复用 schoolLoggedIn flag 自查：
 *   爬虫的 in-flight session 在内存里，事件是唯一明确告诉它"这个用户从这一刻起
 *   不允许借 cookies"的方式；纯靠"下次调用前查 Redis"会有竞态窗口。
 */
@Data
public class UserLogoutEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final String userId;
    private final Long logoutTime;

    public UserLogoutEvent(Object source, String userId) {
        super(source);
        this.userId = userId;
        this.logoutTime = System.currentTimeMillis();
    }
}
