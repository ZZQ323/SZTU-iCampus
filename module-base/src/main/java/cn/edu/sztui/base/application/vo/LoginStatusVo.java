package cn.edu.sztui.base.application.vo;

import cn.edu.sztui.base.domain.model.login.LoginType;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 登录状态 VO（修复版 - 包含用户信息）
 * <p>
 * 用于 GET /auth/v1/status 接口返回
 * <p>
 * 修复：如果已登录，同时返回用户信息，解决小程序重启后本地信息丢失的问题
 */
@Data
public class LoginStatusVo {

    /** 是否已登录 */
    @JsonProperty("logined")
    private boolean logined;

    /** 可用登录方式 */
    private List<LoginType> loginTypes;

    /** 状态检查时间（用于缓存判断） */
    private Long statusTime;

    /** Cookie 是否即将过期 */
    private boolean cookieExpiringSoon;

    // ==================== 用户信息（已登录时返回）====================

    /** 学号 */
    private String userId;

    /** 真实姓名 */
    private String realName;

    /** 性别 */
    private String gender;

    /** 学校名称 */
    private String schoolName;

    /** 头像 URL */
    private String avatarURL;

    /**
     * 从 LoginResultsVo 转换（包含用户信息）
     */
    public static LoginStatusVo from(LoginResultsVo result) {
        LoginStatusVo vo = new LoginStatusVo();
        vo.setLogined(result.isLogined());
        vo.setLoginTypes(result.getLoginTypes());
        vo.setStatusTime(System.currentTimeMillis());

        // 【修复】如果已登录，同时拷贝用户信息
        if (result.isLogined()) {
            vo.setUserId(result.getUserId());
            vo.setRealName(result.getRealName());
            vo.setGender(result.getGender());
            vo.setSchoolName(result.getSchoolName());
            vo.setAvatarURL(result.getAvatarURL());
        }

        return vo;
    }
}