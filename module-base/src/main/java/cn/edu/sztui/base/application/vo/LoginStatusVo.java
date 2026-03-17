package cn.edu.sztui.base.application.vo;

import cn.edu.sztui.base.domain.model.login.LoginType;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 登录状态 VO
 * <p>
 * 用于返回当前登录状态的查询结果
 */
@Data
public class LoginStatusVo {

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
     * 支持的登录方式（字符串形式，方便前端处理）
     */
    private List<String> loginTypes;

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

    /**
     * 从 LoginResultsVo 转换
     */
    public static LoginStatusVo from(LoginResultsVo result) {
        LoginStatusVo vo = new LoginStatusVo();
        vo.setLogined(result.isLogined());
        vo.setUserId(result.getUserId());
        vo.setRealName(result.getRealName());
        vo.setGender(result.getGender());
        vo.setSchoolName(result.getSchoolName());
        vo.setAvatarURL(result.getAvatarURL());
        vo.setSessionInvalid(result.isSessionInvalid());

        // 转换 LoginType 枚举为字符串
        if (result.getLoginTypes() != null) {
            vo.setLoginTypes(
                    result.getLoginTypes().stream()
                            .map(LoginType::name)
                            .collect(Collectors.toList())
            );
        }

        return vo;
    }
}