package cn.edu.sztui.base.application.service;

import cn.edu.sztui.base.application.dto.command.LoginRequestCommand;
import cn.edu.sztui.base.application.vo.LoginResultsVo;
import cn.edu.sztui.base.application.vo.LoginStatusVo;

import java.util.List;

/**
 * 认证服务接口（重构版）
 * <p>
 * 职责拆分：
 * <ul>
 *   <li>{@link #getStatus()} - 状态查询（轻量，可缓存）</li>
 *   <li>{@link #initSession()} - 会话初始化（首次/重建 Cookie）</li>
 *   <li>{@link #refreshSession()} - 会话刷新（仅刷新 SESSION_ID）</li>
 *   <li>{@link #loginFrame(LoginRequestCommand)} - 登录学校系统</li>
 * </ul>
 */
public interface AuthService {

    // ==================== 状态查询（新增） ====================

    /**
     * 获取登录状态（轻量级，优先读缓存）
     * <p>
     * 前端可高频调用此接口，不会每次触发 Playwright。
     * 缓存 TTL 30 秒，平衡实时性与性能。
     *
     * @return LoginStatusVo 包含登录状态和可用登录方式
     */
    LoginStatusVo getStatus();

    /**
     * 检测小程序账号的 session 是否存在（仅检查 Redis）
     * <p>
     * 注意：这只表示 Redis 中有 ProxySession 记录，不代表 Cookie 有效
     */
    boolean getSessionStatus();

    /**
     * 获取历史登录过的学号列表
     */
    List<String> getPossibleUsrId();

    // ==================== 会话管理（重构） ====================

    /**
     * 初始化会话（强制重建 Cookie）
     * <p>
     * 使用场景：
     * <ul>
     *   <li>首次进入需要登录的模块</li>
     *   <li>Cookie 已过期或失效</li>
     *   <li>前端主动请求重新初始化</li>
     * </ul>
     * <p>
     * 会清除旧的状态缓存，强制通过 Playwright 获取最新状态。
     *
     * @return LoginResultsVo 包含登录状态、可用登录方式等完整信息
     */
    LoginResultsVo initSession();

    /**
     * 刷新会话（仅刷新 SESSION_ID，延长有效期）
     * <p>
     * 前置条件：当前已登录学校后端
     * <p>
     * 使用场景：
     * <ul>
     *   <li>定期刷新保持会话活跃</li>
     *   <li>Cookie 即将过期时主动刷新</li>
     * </ul>
     *
     * @return LoginResultsVo
     * @throws cn.edu.sztui.common.util.exception.BusinessException 如果未登录或会话已过期
     */
    LoginResultsVo refreshSession();

    /**
     * 原有的 refresh 方法（兼容旧代码，内部调用 initSession）
     *
     * @deprecated 请使用 {@link #initSession()} 或 {@link #refreshSession()}
     */
    @Deprecated
    LoginResultsVo refresh();

    // ==================== 登录/登出 ====================

    /**
     * 请求发送短信验证码
     *
     * @param usrId 学号
     * @return 更新后的 cookiesJson（供前端通过 response header 返回）
     */
    String getSms(String usrId);

    /**
     * 登录学校系统
     *
     * @param cmd 登录参数（学号、密码/验证码、登录方式）
     * @return LoginResultsVo 登录结果
     */
    LoginResultsVo loginFrame(LoginRequestCommand cmd);

    /**
     * 登出学校系统
     * @return LoginResultsVo
     */
    LoginResultsVo logout();
}