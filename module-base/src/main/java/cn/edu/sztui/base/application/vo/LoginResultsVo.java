package cn.edu.sztui.base.application.vo;

import cn.edu.sztui.base.domain.model.login.LoginType;
import lombok.Data;

import java.util.List;

/**
 * 登录结果 VO
 * <p>
 * 用于返回登录/刷新会话的结果
 */
@Data
public class LoginResultsVo {

    /**
     * 是否已登录
     */
    private boolean logined;

    /**
     * 用户学号
     */
    private String userId;

    /**
     * 用户真实姓名
     */
    private String realName;

    /**
     * 性别
     */
    private String gender;

    /**
     * 学校名称
     */
    private String schoolName;

    /**
     * 头像 URL
     */
    private String avatarURL;

    /**
     * 支持的登录方式
     */
    private List<LoginType> loginTypes;

    /**
     * 学校 cookies（明文 JSON）—— 前端需存储并在后续请求中携带
     */
    private String cookiesJson;

    /**
     * ⭐ 会话是否无效（需要重新初始化）
     * <p>
     * 当遇到以下情况时为 true：
     * - 错误页面（"当前界面遇到了一些问题"）
     * - 会话异常
     * - Cookie 已被服务器清除
     * <p>
     * 前端收到此标志时应该：
     * 1. 清除本地 userInfo
     * 2. 提示用户需要重新登录
     */
    private boolean sessionInvalid;
}