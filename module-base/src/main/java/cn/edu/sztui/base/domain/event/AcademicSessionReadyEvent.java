package cn.edu.sztui.base.domain.event;

import lombok.Data;
import org.springframework.context.ApplicationEvent;

/**
 * 教务系统会话就绪事件
 * <p>
 * 当用户成功初始化教务系统（/acdm/v1/init 完成，jwxt 子域 cookies 已入 Redis）后发布此事件。
 * module-stream 的 listener 据此对该 userId 的 acdm-* 数据源做一次性爬取。
 * <p>
 * 为什么单独一个事件而不是复用 {@link UserLoginEvent}：
 * <ul>
 *   <li>登录事件发布时 jwxt cookies 还未获取（前端紧接着调 /acdm/v1/init 才拿到）</li>
 *   <li>acdm-* 源的爬取依赖 jwxt 子域 cookies；让"cookies 就绪"的源头来 push 最干净</li>
 *   <li>session 过期后再 init 也会重新发布这个事件 —— 事件是"就绪"语义，不是"首次登录"语义</li>
 * </ul>
 */
@Data
public class AcademicSessionReadyEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final String userId;

    private final Long readyAt;

    public AcademicSessionReadyEvent(Object source, String userId) {
        super(source);
        this.userId = userId;
        this.readyAt = System.currentTimeMillis();
    }
}
