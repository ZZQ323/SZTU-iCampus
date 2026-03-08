package cn.edu.sztui.base.application.vo;

import cn.edu.sztui.base.domain.model.login.LoginType;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 登录状态 VO（精简版）
 * <p>
 * 用于 GET /auth/v1/status 接口返回。
 * <p>
 * 删除的字段：
 * <ul>
 *   <li>cookieExpiringSoon - 不再做 Cookie 过期预测</li>
 *   <li>statusTime - 不再做状态缓存</li>
 * </ul>
 */
@Data
public class LoginStatusVo {

    /**
     * 是否已登录学校
     */
    @JsonProperty("logined")
    private boolean logined;

    /**
     * 可用的登录方式（未登录时返回）
     */
    private List<LoginType> loginTypes;

    /**
     * 用户学号
     */
    private String userId;

    /**
     * 用户姓名
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
     * 头像URL
     */
    private String avatarURL;

    /**
     * 从 LoginResultsVo 转换
     */
    public static LoginStatusVo from(LoginResultsVo result) {
        LoginStatusVo vo = new LoginStatusVo();
        vo.setLogined(result.isLogined());
        vo.setLoginTypes(result.getLoginTypes());
        vo.setUserId(result.getUserId());
        vo.setRealName(result.getRealName());
        vo.setGender(result.getGender());
        vo.setSchoolName(result.getSchoolName());
        vo.setAvatarURL(result.getAvatarURL());
        return vo;
    }
}