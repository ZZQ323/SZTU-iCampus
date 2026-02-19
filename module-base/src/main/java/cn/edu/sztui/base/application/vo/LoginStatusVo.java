package cn.edu.sztui.base.application.vo;

import cn.edu.sztui.base.domain.model.loginhandle.LoginType;
import lombok.Data;

import java.util.List;

/**
 * 登录状态查询 VO（轻量级，可缓存）
 * <p>
 * 用于 GET /auth/v1/status 接口，与 LoginResultsVo 的区别：
 * - LoginStatusVo：仅包含状态判断所需的最小字段，支持短 TTL 缓存
 * - LoginResultsVo：包含完整用户信息，用于登录成功后返回
 */
@Data
public class LoginStatusVo {

    /** 是否已登录学校后端 */
    private boolean logined;

    /** 可用的登录方式（仅未登录时有意义） */
    private List<LoginType> loginTypes;

    /** 状态获取时间戳（用于前端判断是否需要主动刷新） */
    private Long statusTime;

    /** Cookie 是否即将过期（前端可据此提前刷新） */
    private boolean cookieExpiringSoon;

    /**
     * 从 LoginResultsVo 转换
     */
    public static LoginStatusVo from(LoginResultsVo result) {
        LoginStatusVo status = new LoginStatusVo();
        status.setLogined(result.isLogined());
        status.setLoginTypes(result.getLoginTypes());
        status.setStatusTime(System.currentTimeMillis());
        status.setCookieExpiringSoon(false); // 默认值，由调用方根据实际情况设置
        return status;
    }
}
