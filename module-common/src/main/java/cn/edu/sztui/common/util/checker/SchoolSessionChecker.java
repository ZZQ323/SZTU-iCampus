package cn.edu.sztui.common.util.checker;

import cn.edu.sztui.common.util.smarthttp.SmartResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 学校会话状态检测工具
 * <p>
 * 统一检测学校 Cookie 是否有效，避免在各业务模块重复判断逻辑。
 * <p>
 * 使用场景：
 * <ul>
 *   <li>认证模块：doRefreshCookies 检测登录状态</li>
 *   <li>教务模块：爬取课表前检测会话有效性</li>
 *   <li>公告模块：爬取公告前检测会话有效性</li>
 * </ul>
 * <p>
 * 错误码约定：
 * <ul>
 *   <li>401 - Token 无效（由 JwtAuthFilter 处理）</li>
 *   <li>403 - Cookie 无效（由本工具检测，业务层抛出）</li>
 * </ul>
 * <p>
 * 位置：cn.edu.sztui.common.util.checker.SchoolSessionChecker
 */
public class SchoolSessionChecker {

    // ==================== 登录页 URL 特征 ====================

    /** 认证中心 - ActionAuthChain */
    private static final String AUTH_CENTER_PATTERN = "/idp/authcenter/ActionAuthChain";

    /** 认证引擎 */
    private static final String AUTH_ENGINE_PATTERN = "/idp/AuthnEngine";

    /** WebVPN 网关入口（仅SMS） */
    private static final String GATEWAY_FIRST = "entityId=webvpn";

    /** Home 网关入口（SMS+PASSWORD） */
    private static final String GATEWAY_SECOND = "entityId=home";

    // ==================== 已登录页面 URL 特征 ====================

    /** 门户首页 */
    private static final String PORTAL_INDEX = "bmportal/index.portal";

    /** 教务系统主页 */
    private static final String ACDM_MAIN = "/jsxsd/framework/xsMain";

    /** 教务系统入口 */
    private static final String ACDM_ENTRY = "/jsxsd/";

    // ==================== 错误页面特征 ====================

    /** 错误信息页面标题 */
    private static final String ERROR_PAGE_TITLE = "错误信息";

    /** 错误信息页面提示文字 */
    private static final String ERROR_PAGE_HINT = "请关闭浏览器页面重试";

    // ==================== 核心检测方法 ====================

    /**
     * 检测是否被重定向到登录页（Cookie 无效/过期）
     * <p>
     * 当检测到此情况时，业务层应抛出 403 错误，引导前端重新初始化会话。
     * <p>
     * 使用示例：
     * <pre>
     * SmartResponse response = smartHttpClient.get(someUrl, session);
     * if (SchoolSessionChecker.isRedirectedToLogin(response)) {
     *     throw new BusinessException(
     *         SysReturnCode.BASE_PROXY.getCode(),
     *         "学校会话已过期",
     *         ResultCodeEnum.FORBIDDEN.getCode()  // 403
     *     );
     * }
     * </pre>
     *
     * @param response SmartHttpClient 的响应对象
     * @return true 表示 Cookie 无效，被重定向到了登录页
     */
    public static boolean isRedirectedToLogin(SmartResponse response) {
        if (response == null) {
            return true;
        }

        String finalUrl = response.getFinalUrl();
        String body = response.getBody();

        // 1. URL 特征检测
        if (finalUrl != null) {
            if (finalUrl.contains(AUTH_CENTER_PATTERN) ||
                    finalUrl.contains(AUTH_ENGINE_PATTERN)) {
                return true;
            }

            // 带有 entityId 参数的认证页面
            if (finalUrl.contains(GATEWAY_FIRST) || finalUrl.contains(GATEWAY_SECOND)) {
                return true;
            }
        }

        // 2. Body 特征检测（错误信息页面）
        if (body != null) {
            if (body.contains(ERROR_PAGE_TITLE) && body.contains(ERROR_PAGE_HINT)) {
                return true;
            }

            // 登录表单特征（但需排除已登录页面）
            if (body.contains("j_username") && !isLoggedInByBody(body)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检测是否已登录学校系统
     *
     * @param response SmartHttpClient 的响应对象
     * @return true 表示已登录
     */
    public static boolean isLoggedIn(SmartResponse response) {
        if (response == null) {
            return false;
        }

        String finalUrl = response.getFinalUrl();
        String body = response.getBody();

        // 1. URL 特征检测
        if (finalUrl != null) {
            if (finalUrl.contains(PORTAL_INDEX) ||
                    finalUrl.contains(ACDM_MAIN)) {
                return true;
            }
        }

        // 2. Body 特征检测
        return isLoggedInByBody(body);
    }

    /**
     * 根据响应体判断是否已登录
     */
    private static boolean isLoggedInByBody(String body) {
        if (body == null) {
            return false;
        }

        // 门户页面特征
        if (body.contains("bmportal") && body.contains("userInfo")) {
            return true;
        }

        // 教务系统特征
        if (body.contains("个人中心") || body.contains("我的课表")) {
            return true;
        }

        // 用户信息特征（realName 通常出现在已登录页面）
        if (body.contains("\"realName\"") || body.contains("'realName'")) {
            return true;
        }

        return false;
    }

    /**
     * 检测登录页面支持的登录方式
     * <p>
     * 根据最终 URL 和页面内容判断支持 SMS、PASSWORD 或两者都支持。
     *
     * @param response SmartHttpClient 的响应对象
     * @return 支持的登录方式列表（"SMS", "PASSWORD"）
     */
    public static List<String> detectLoginTypes(SmartResponse response) {
        if (response == null) {
            return Collections.singletonList("SMS");
        }

        String finalUrl = response.getFinalUrl();
        String body = response.getBody();
        List<String> types = new ArrayList<>();

        // 根据 URL 判断
        if (finalUrl != null) {
            // entityId=webvpn 通常只支持 SMS
            if (finalUrl.contains(GATEWAY_FIRST)) {
                types.add("SMS");
                return types;
            }

            // entityId=home 通常支持 SMS + PASSWORD
            if (finalUrl.contains(GATEWAY_SECOND)) {
                types.add("SMS");
                types.add("PASSWORD");
                return types;
            }
        }

        // 根据页面内容判断
        if (body != null) {
            if (body.contains("sms_checkcode") || body.contains("短信验证") || body.contains("验证码登录")) {
                types.add("SMS");
            }
            if (body.contains("j_password") || body.contains("密码登录")) {
                types.add("PASSWORD");
            }
        }

        // 默认返回 SMS
        if (types.isEmpty()) {
            types.add("SMS");
        }

        return types;
    }

    /**
     * 检测是否是教务系统页面
     *
     * @param response SmartHttpClient 的响应对象
     * @return true 表示是教务系统页面
     */
    public static boolean isAcademicSystemPage(SmartResponse response) {
        if (response == null || response.getFinalUrl() == null) {
            return false;
        }

        String finalUrl = response.getFinalUrl();
        return finalUrl.contains(ACDM_ENTRY) || finalUrl.contains("jwxt");
    }

    /**
     * 检测是否需要切换教务系统端口（学生/教师角色选择）
     *
     * @param response SmartHttpClient 的响应对象
     * @return true 表示需要切换端口
     */
    public static boolean needsSwitchPort(SmartResponse response) {
        if (response == null || response.getBody() == null) {
            return false;
        }

        String body = response.getBody();
        return body.contains("xsrkxz") || body.contains("角色选择");
    }

    // ==================== 便捷方法 ====================

    /**
     * 快速检查响应是否有效（已登录且不是错误页面）
     *
     * @param response SmartHttpClient 的响应对象
     * @return true 表示响应有效，可以继续处理业务
     */
    public static boolean isValidResponse(SmartResponse response) {
        return response != null
                && response.isSuccess()
                && !isRedirectedToLogin(response);
    }

    /**
     * 获取会话状态描述（用于日志）
     *
     * @param response SmartHttpClient 的响应对象
     * @return 状态描述字符串
     */
    public static String getStatusDescription(SmartResponse response) {
        if (response == null) {
            return "NO_RESPONSE";
        }

        if (isLoggedIn(response)) {
            return "LOGGED_IN";
        }

        if (isRedirectedToLogin(response)) {
            List<String> types = detectLoginTypes(response);
            return "NEED_LOGIN:" + String.join(",", types);
        }

        return "UNKNOWN";
    }
}